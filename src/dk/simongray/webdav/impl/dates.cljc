(ns ^:no-doc dk.simongray.webdav.impl.dates
  "The dates of WebDAV's properties, written and read the same way on every
  platform: the HTTP-date of getlastmodified and the RFC 3339 timestamp of
  creationdate.

  The JDK and JavaScript each print dates their own way, so the dates are
  written from the day count with the arithmetic of Howard Hinnant's
  days_from_civil and civil_from_days."
  (:require [dk.simongray.wary-fetch.date :as date]))

(defn- floor-div
  "The quotient of `a` and `b`, rounded down, as Math.floorDiv gives it."
  [a b]
  (quot (- a (mod a b)) b))

(defn epoch-day
  "The number of days from 1 January 1970 to the day `d` of the month `m`
  of the year `y`."
  [y m d]
  (let [y   (if (<= m 2) (dec y) y)
        era (floor-div y 400)
        yoe (- y (* era 400))
        doy (+ (quot (+ (* 153 (+ m (if (> m 2) -3 9))) 2) 5) (dec d))
        doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
    (+ (* era 146097) doe -719468)))

(defn civil
  "The year, the month and the day of the `day`th day since 1 January 1970."
  [day]
  (let [z   (+ day 719468)
        era (floor-div z 146097)
        doe (- z (* era 146097))
        yoe (quot (- doe (quot doe 1460) (- (quot doe 36524)) (quot doe 146096))
                  365)
        doy (- doe (+ (* 365 yoe) (quot yoe 4) (- (quot yoe 100))))
        mp  (quot (+ (* 5 doy) 2) 153)
        d   (inc (- doy (quot (+ (* 153 mp) 2) 5)))
        m   (if (< mp 10) (+ mp 3) (- mp 9))]
    [(+ yoe (* era 400) (if (<= m 2) 1 0)) m d]))

(defn- pad
  "The number `n` with leading zeros to `width` digits."
  [width n]
  (let [s (str n)]
    (str (apply str (repeat (- width (count s)) "0")) s)))

(defn- fields
  "The year, month, day, hours, minutes, seconds and weekday (0 for Sunday)
  of the instant `ms` in epoch milliseconds, in UTC."
  [ms]
  (let [day     (floor-div ms 86400000)
        seconds (quot (- ms (* day 86400000)) 1000)
        [y m d] (civil day)]
    [y m d
     (quot seconds 3600) (quot (mod seconds 3600) 60) (mod seconds 60)
     (mod (+ day 4) 7)]))

(def weekdays
  "The names of the weekdays in HTTP-dates, from Sunday."
  ["Sun" "Mon" "Tue" "Wed" "Thu" "Fri" "Sat"])

(def months
  "The names of the months in HTTP-dates."
  ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

;; RFC 9110, section 5.6.7
(defn http-date
  "The IMF-fixdate of the instant `t`, e.g. Tue, 13 Oct 2026 15:04:05 GMT."
  [t]
  (let [[y m d hh mm ss wd] (fields (inst-ms t))]
    (str (weekdays wd) ", " (pad 2 d) " " (months (dec m)) " " (pad 4 y) " "
         (pad 2 hh) ":" (pad 2 mm) ":" (pad 2 ss) " GMT")))

;; RFC 4918, section 15.1, and RFC 3339, section 5.6
(defn timestamp
  "The RFC 3339 date-time of the instant `t` in UTC, e.g.
  2026-10-13T15:04:05Z."
  [t]
  (let [[y m d hh mm ss] (fields (inst-ms t))]
    (str (pad 4 y) "-" (pad 2 m) "-" (pad 2 d) "T"
         (pad 2 hh) ":" (pad 2 mm) ":" (pad 2 ss) "Z")))

(defn instant
  "The instant `ms` milliseconds after the epoch, as the platform keeps
  instants: a java.util.Date or a js/Date."
  [ms]
  #?(:clj  (java.util.Date. (long ms))
     :cljs (js/Date. ms)))

(defn parse-http-date
  "The instant of the HTTP-date `s` in any of the three forms of RFC 9110,
  or nil when it isn't one."
  [s]
  (some-> s date/epoch-ms instant))

(def timestamp-pattern
  "The pattern of an RFC 3339 date-time, with its parts as groups."
  #"\s*(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?([Zz]|[+-]\d{2}:\d{2})\s*")

(defn- offset-ms
  "The offset from UTC of the RFC 3339 `zone`, e.g. +02:00, in
  milliseconds."
  [zone]
  (if (#{"Z" "z"} zone)
    0
    (* (if (= \- (first zone)) -1 1)
       (+ (* 60 (parse-long (subs zone 1 3))) (parse-long (subs zone 4 6)))
       60000)))

(defn parse-timestamp
  "The instant of the RFC 3339 date-time `s`, or nil when it isn't one."
  [s]
  (when-let [[_ y m d hh mm ss fraction zone] (some->> s (re-matches timestamp-pattern))]
    (let [[y m d hh mm ss] (map parse-long [y m d hh mm ss])
          millis           (if fraction (parse-long (subs (str fraction "00") 0 3)) 0)]
      (when (and (<= 1 m 12) (<= 1 d 31) (< hh 24) (< mm 60) (< ss 61))
        (instant (- (+ (* 86400000 (epoch-day y m d))
                       (* 1000 (+ (* 3600 hh) (* 60 mm) ss))
                       millis)
                    (offset-ms zone)))))))

(comment
  (http-date #inst "2026-10-13T15:04:05Z")
  (timestamp #inst "1969-12-31T23:59:59Z")
  (parse-timestamp "2026-10-13T17:04:05.5+02:00")
  (parse-http-date "Sunday, 06-Nov-94 08:49:37 GMT")
  #_.)
