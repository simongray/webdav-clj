(ns ^:no-doc dk.simongray.webdav.impl.html
  "The plain HTML page that a GET of a collection answers with: a list of
  links to its members, for a browser."
  (:require [clojure.string :as str]))

(defn escape
  "The text `s` with the characters that mark up HTML replaced by their
  character references."
  [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn index
  "The HTML of a page titled `title` with a list of the `links`, each a
  pair of an href and its text."
  [title links]
  (str "<!DOCTYPE html>\n"
       "<html><head><meta charset=\"utf-8\"><title>" (escape title) "</title></head>\n"
       "<body><h1>" (escape title) "</h1>\n"
       "<ul>\n"
       (str/join (for [[href text] links]
                   (str "<li><a href=\"" (escape href) "\">" (escape text) "</a></li>\n")))
       "</ul></body></html>\n"))

(comment
  (index "/dav/<notes>/" [["/dav/" "../"] ["/dav/a%20b.txt" "a b.txt"]])
  #_.)
