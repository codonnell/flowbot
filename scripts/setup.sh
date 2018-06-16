#!/usr/bin/env bash

psql -c "create user flowbot with password 'flowbot';"
psql -c "create database flowbot with owner flowbot;"
psql -d flowbot -c "create extension \"pgcrypto\";"
