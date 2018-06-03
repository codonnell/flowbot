# flowbot

FIXME: description

## Installation

Download from http://example.com/FIXME.

### Local Development

First you'll need to install the [clojure command line tools](https://clojure.org/guides/getting_started) and [postgres](https://www.postgresql.org/download/) if they are not already installed. This has been tested with postgres 10.4.

Create a postgres user with name `flowbot` and password `flowbot` and database `flowbot`. This can be done by running the command `sudo -u postgres ./scripts/setup.sh`. (This assumes the user postgres is a psql superuser, which is standard.) If you wish to use different development user, password, or database name, then you'll need to create them yourself and alter the environment variables accordingly in `flowbot.env`.

Next, to apply database migrations, you can run `make migrate`. (And you can run `make rollback` to revert the latest migration.)

To start a repl, run `make repl`.

## Usage

FIXME: explanation

    $ java -jar flowbot-0.1.0-standalone.jar [args]

## Options

FIXME: listing of options this app accepts.

## Examples

...

### Bugs

...

### Any Other Sections
### That You Think
### Might be Useful

## License

Copyright © 2018 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
