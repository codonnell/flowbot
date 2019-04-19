SHELL := /bin/bash

.PHONY: test

repl:
	source flowbot.env && source flowbot_secret.env && clj -A:dev

test:
	clj -A\:test

clean:
	rm flowbot.jar

uberjar:
	clojure -A\:pack mach.pack.alpha.capsule flowbot.jar --application-id flowbot --application-version "$(git describe --tags)" -m flowbot.core

migrate:
	source flowbot.env && source flowbot_secret.env && clojure -m flowbot.migrate.up

rollback:
	source flowbot.env && source flowbot_secret.env && clojure -m flowbot.migrate.down

pack: clean uberjar
