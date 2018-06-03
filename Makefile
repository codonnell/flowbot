SHELL := /bin/bash

serve:
	source flowbot.env && clojure -m flowbot.service

test:
	clojure -A\:test

clean:
	rm -rf target flowbot.jar

uberjar:
	clojure -A\:uberjar

outdated:
	clojure -Aoutdated -a outdated

serve-jar:
	source flowbot.env && java -jar flowbot.jar -m flowbot.service

migrate:
	source flowbot.env && clojure -m flowbot.migrate.up

rollback:
	source flowbot.env && clojure -m flowbot.migrate.down

pack: clean uberjar
