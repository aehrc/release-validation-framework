# Changelog
All notable changes to this project will be documented in this file.

## Unreleased

### Breaking
- MySQL schema change: the `identifier_*` tables declare their columns in the RF2 Identifier file's order (alternateIdentifier first), because releases are loaded by position. MySQL binary archives (`rvf.release.mysql.binary.storage`) written before this hold the old order; remove them and let RVF repopulate them.

## 9.0.0

### Breaking
- Update to Java 25.

## 6.5.0 / 7.0.0

### Breaking
- Upgrade to MySQL 8.

## 5.0.0

### Breaking
- Update libraries to use BOMs and remove explicit version numbers from dependencies.
- Update to Java 17.

## 3.14.0

### Breaking
- A reorganisation of the project structure to make it a simple one level with one pom.xml.

## 3.1.2

### Breaking
- MySQL schema change for the MySQL binary cache files (stored under {{rvf.release.mysql.binary.storage.cloud.path}}). Please remove these files and allow RVF to automatically repopulate the cache as needed using the new schema.
