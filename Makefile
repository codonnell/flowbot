SHELL := /bin/bash

.PHONY: test

repl:
	source flowbot.env && source flowbot_secret.env && clj -A:dev

test:
	clj -A\:test

clean:
	rm -rf target flowbot.jar

uberjar:
	clojure -A\:uberjar

outdated:
	clojure -Aoutdated -a outdated

serve-jar:
	source flowbot.env && source flowbot_secret.env && java -jar flowbot.jar -m flowbot.service

migrate:
	source flowbot.env && source flowbot_secret.env && clojure -m flowbot.migrate.up

rollback:
	source flowbot.env && source flowbot_secret.env && clojure -m flowbot.migrate.down

pack: clean uberjar
