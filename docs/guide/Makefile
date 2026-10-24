# JXPilot user guide.
#
#   make              build/index.html (needs asciidoctor: `gem install asciidoctor` or `brew install asciidoctor`)
#   make generate     rewrite generated/*.adoc from the code (needs JDK 25 and Maven)
#   make clean
#
# The generated tables are committed; the Maven build fails when they no longer match the code.

ASCIIDOCTOR ?= asciidoctor
MVN ?= mvn
MVNFLAGS ?= -q
VERSION ?= dev
BUILD := build

SOURCES := $(wildcard *.adoc) $(wildcard generated/*.adoc)
IMAGES := $(wildcard images/*.png) $(wildcard images/*/*.png) $(wildcard images/*/*.gif)

.PHONY: html generate clean

html: $(BUILD)/index.html

$(BUILD)/index.html: $(SOURCES) $(IMAGES)
	mkdir -p $(BUILD)/images
	cp -R images/. $(BUILD)/images/
	$(ASCIIDOCTOR) --failure-level=WARN -a revnumber=$(VERSION) -o $(BUILD)/index.html JXPilotGuide.adoc

generate:
	cd ../.. && MAVEN_OPTS= $(MVN) $(MVNFLAGS) -pl dist -am test -Dtest=GeneratedDocsTest \
		-Dsurefire.failIfNoSpecifiedTests=false -Djxpilot.regenerateDocs=true

clean:
	rm -rf $(BUILD)
