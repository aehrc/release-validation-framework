FROM maven:3.6.3-openjdk-17 AS builder
COPY . /usr/src/app
WORKDIR /usr/src/app
RUN mvn clean install -DskipTests=true

FROM aehrc/jre:openjdk-17
LABEL maintainer="SNOMED International <tooling@snomed.org>"

ARG SUID=1042
ARG SGID=1042

VOLUME /tmp

RUN apk update
RUN apk add git

# Create a working directory
RUN mkdir /app
WORKDIR /app

# Both clones below are PINNED, and must stay pinned.
#
# Unpinned, this image is not reproducible: what it contains depends on the day
# it was built, so an image that works can stop working with no change to this
# repository. That is not hypothetical. It broke production on 2026-08-07 when a
# two-year-old image was rebuilt, and it was demonstrated again on 2026-08-17: a
# rebuild picked up assertions HEAD 0160dd2e, four files had been removed
# upstream since fad36466, and RVF died during startup with
#
#   FileNotFoundException: ./snomed-release-validation-assertions/scripts/
#     release-type/release-type-delta-previous-snapshot-validation-inferred-
#     relationship_EDITION.sql
#
# That is a startup failure, not a content failure - the nightly produces no
# report at all. Any change that requires rebuilding this image (a code fix, a
# base image bump) would have shipped it.
ARG DROOLS_RULES_REF=55795d5d19b1db99d2f5757e6aa397014aaaf268
RUN git clone https://github.com/IHTSDO/snomed-drools-rules.git \
    && git -C snomed-drools-rules checkout --quiet ${DROOLS_RULES_REF}

# fad36466 is the commit the chart's manifest.xml resolves against. It is what
# the last known-good image carries and what every green nightly to date has
# used. Bumping it means re-checking that every sqlFile reference in
# testscripts/manifest.xml still resolves - a missing one stops RVF booting.
ARG ASSERTIONS_REF=fad36466277ca633e0bc6844a3b4a83d3698ea97
RUN git clone https://github.com/IHTSDO/snomed-release-validation-assertions.git \
    && git -C snomed-release-validation-assertions checkout --quiet ${ASSERTIONS_REF}

RUN mkdir /app/store
RUN mkdir /app/store/releases

# Copy necessary files
COPY --from=builder /usr/src/app/target/release-validation-framework*.jar rvf-api.jar

# Create the rvf user
RUN addgroup -g $SGID rvf && \
    adduser -D -u $SUID -G rvf rvf

# Change permissions.
RUN chown -R rvf:rvf /app

# Run as the rvf user.
USER rvf

ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","rvf-api.jar"]
