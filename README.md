# flowbot

FIXME: description

## Installation

Download from http://example.com/FIXME.

### Local Development

Some example environment variables are set in `flowbot.env` with a couple of lines commented out. In order to run the application, you will need to set those values in `flowbot_secret.env`. Additionally, if you wish to overwrite values from `flowbot.env`, you may do so in `flowbot_secret.env`.

First you'll need to install the [clojure command line tools](https://clojure.org/guides/getting_started) and [postgres](https://www.postgresql.org/download/) if they are not already installed. This has been tested with postgres 10.4.

Create a postgres user with name `flowbot` and password `flowbot` and database `flowbot`. Add the `pgcrypto` extension to the newly created `flowbot` database. This can be done by running the command `sudo -u postgres ./scripts/setup.sh`. (This assumes the user postgres is a psql superuser, which is standard.) If you wish to use different development user, password, or database name, then you'll need to create them yourself and alter the environment variables accordingly in `flowbot_secret.env`.

Next, to apply database migrations, you can run `make migrate`. (And you can run `make rollback` to revert the latest migration.)

To start a repl, run `make repl`.

## Usage

To build an ubarjar, run `make pack`, which will create an uberjar tagged with the latest git tag of this repo. You can run the jar with:

    $ java -jar flowbot.jar

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
