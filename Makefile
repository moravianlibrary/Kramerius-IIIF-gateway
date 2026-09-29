# Task entry point. Every verb here works the same way it does in any other repo:
#   make help   make check
#
# TWO PROJECTS, ONE REPOSITORY. iiif-kramerius/ is the library and gateway/ is the service; each has
# its own reactor, VERSION and CHANGELOG. Every verb below runs across both in dependency order,
# because the gateway resolves the library as a released artifact rather than as a sibling module —
# which is what keeps the dependency one-way.
#
# That ordering is why `lib-install` exists and why most targets depend on it: the shared build-config
# POM and the library have to be in the local repository before the gateway reactor can resolve them.
# Build the gateway alone after changing a library file and you will be testing the previous library.
#
# Durable trade-offs are recorded in docs/DECISIONS.md.

MAKEFLAGS += --no-print-directory
.DEFAULT_GOAL := help

# ─── Toolchain ────────────────────────────────────────────────────────────────
MVN            ?= ./mvnw
DOCKER         ?= $(shell command -v podman 2>/dev/null || command -v docker 2>/dev/null)

# ─── Projects ─────────────────────────────────────────────────────────────────
LIB            := iiif-kramerius
APP            := gateway
LIB_POM        := $(LIB)/pom.xml
APP_POM        := $(APP)/pom.xml

# ─── Release ──────────────────────────────────────────────────────────────────
VERSION        ?= $(shell cat $(APP)/VERSION)
LIB_VERSION    ?= $(shell cat $(LIB)/VERSION)
REGISTRY       ?=
IMAGE          ?= iiif-gateway
IMAGE_REF      := $(if $(REGISTRY),$(REGISTRY)/,)$(IMAGE)
COMMIT         := $(shell git rev-parse --short HEAD 2>/dev/null || echo unknown)

# ─── Dev database ─────────────────────────────────────────────────────────────
DB_CONTAINER   ?= iiif-db
VIEWER_PORT    ?= 18090
VIEWER_IMAGE_NAME ?= iiif-viewers
VIEWER_VERSION  ?= 1
VIEWER_IMAGE    ?= localhost/$(VIEWER_IMAGE_NAME):$(VIEWER_VERSION)
VIEWER_IMAGE_REF := $(if $(REGISTRY),$(REGISTRY)/,)$(VIEWER_IMAGE_NAME)
# Where `smoke` and `viewer-check` expect to find a running gateway.
SMOKE_BASE     ?= http://localhost:8080
DB_NAME        ?= iiif
# Separate on purpose: `test-integration` must not share a job queue with a running worker.
DB_TEST_NAME   ?= iiif_test
DB_USER        ?= iiif
DB_PASSWORD    ?= iiif
DB_PORT        ?= 5432
DB_IMAGE       ?= docker.io/library/postgres:17-alpine

help: ## Show this help
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ─── Quality ──────────────────────────────────────────────────────────────────

lib-install: ## Build the library and put it in the local repo (what the gateway resolves)
	@# The shared build-config POM first: the library's own POMs name it as their parent, so a
	@# consumer resolving the installed library needs it present. Installing the library alone left
	@# the gateway unable to read its dependencies' descriptors at all.
	$(MVN) -q -N install
	$(MVN) -q -DskipTests install -f $(LIB_POM)

format: ## Rewrite sources to the house format (google-java-format, 100 cols)
	$(MVN) -q spotless:apply -f $(LIB_POM)
	$(MVN) -q spotless:apply -f $(APP_POM)

format-check: ## Fail if anything is not formatted
	$(MVN) -q spotless:check -f $(LIB_POM)
	$(MVN) -q spotless:check -f $(APP_POM)

lint: lib-install ## Static analysis (SpotBugs)
	$(MVN) -q -DskipTests verify -f $(LIB_POM)
	$(MVN) -q -DskipTests verify -f $(APP_POM)

typecheck: lib-install ## Compile with -Xlint:all -Werror
	$(MVN) -q -DskipTests compile -f $(LIB_POM)
	$(MVN) -q -DskipTests compile -f $(APP_POM)


# Three things must agree, not two: each project's VERSION against its own POMs, and the library
# version the GATEWAY pins. A stale pin is the quiet failure — the gateway builds green against
# yesterday's manifest code.
version-check: ## Fail if either VERSION file and its POMs have drifted apart
	@fail=0; \
	lib_pom=$$($(MVN) -q -N help:evaluate -Dexpression=project.version -DforceStdout -f $(LIB_POM)); \
	app_pom=$$($(MVN) -q -N help:evaluate -Dexpression=project.version -DforceStdout -f $(APP_POM)); \
	lib_file=$$(cat $(LIB)/VERSION); app_file=$$(cat $(APP)/VERSION); \
	if [ "$$lib_pom" != "$$lib_file" ]; then \
		echo "$(LIB)/VERSION is $$lib_file but its POMs say $$lib_pom — run: make set-lib-version VERSION=$$lib_file"; fail=1; \
	fi; \
	if [ "$$app_pom" != "$$app_file" ]; then \
		echo "$(APP)/VERSION is $$app_file but its POMs say $$app_pom — run: make set-version VERSION=$$app_file"; fail=1; \
	fi; \
	pinned=$$($(MVN) -q -N help:evaluate -Dexpression=library.version -DforceStdout); \
	if [ "$$pinned" != "$$lib_file" ]; then \
		echo "the gateway builds against library $$pinned but $(LIB)/VERSION is $$lib_file — run: make set-lib-version VERSION=$$lib_file"; fail=1; \
	fi; \
	[ $$fail -eq 0 ] || exit 1; \
	echo "library $$lib_file, gateway $$app_file"

set-version: ## Set the GATEWAY version — spi, rest, gateway (requires VERSION=x.y.z)
	$(if $(VERSION),,$(error VERSION is required))
	@echo "$(VERSION)" > $(APP)/VERSION
	$(MVN) -q versions:set -DnewVersion=$(VERSION) -DprocessAllModules=true \
		-DgenerateBackupPoms=false -f $(APP_POM)
	@echo "gateway version set to $(VERSION); update $(APP)/CHANGELOG.md before tagging"

set-lib-version: ## Set the LIBRARY version — model, build, embedded (requires VERSION=x.y.z)
	$(if $(VERSION),,$(error VERSION is required))
	@echo "$(VERSION)" > $(LIB)/VERSION
	$(MVN) -q versions:set -DnewVersion=$(VERSION) -DprocessAllModules=true \
		-DgenerateBackupPoms=false -f $(LIB_POM)
	@# And the pin the gateway resolves it through, or the next gateway build uses the old jar.
	$(MVN) -q -N versions:set-property -Dproperty=library.version -DnewVersion=$(VERSION) \
		-DgenerateBackupPoms=false
	@echo "library version set to $(VERSION); update $(LIB)/CHANGELOG.md before tagging"

config-check: ## Check config/libraries.json against its schema
	./config/check.sh

check: version-check config-check format-check typecheck lint test ## The gate: run everything CI runs

# ─── Tests ────────────────────────────────────────────────────────────────────

# IIIF v3 schema validation is part of `test`, not a separate target: the IIIF project publishes no
# validator image, so every fixture test asserts its manifest against the schema vendored at
# $(LIB)/iiif-kramerius-model/src/test/resources/iiif/. That keeps the check offline and inside
# the gate.
test: lib-install ## Unit tests, incl. IIIF v3 schema validation (offline, fixture-driven)
	$(MVN) -q test -f $(LIB_POM)
	$(MVN) -q test -f $(APP_POM)

test-integration: lib-install ## Tests needing a real Postgres (see db-up). Gateway only.
	@# The exit status is maven's, not grep's. Piping into grep made this target report success on a
	@# failing build — it exited 0 through `|| true` while the log said BUILD FAILURE, which is the
	@# one thing a test target must never do.
	@log=$$(mktemp); \
	IIIF_TEST_DB_URL=jdbc:postgresql://localhost:$(DB_PORT)/$(DB_TEST_NAME) \
	IIIF_TEST_DB_USER=$(DB_USER) \
	IIIF_TEST_DB_PASSWORD=$(DB_PASSWORD) \
	$(MVN) verify -Pintegration -f $(APP_POM) > "$$log" 2>&1; \
	status=$$?; \
	grep -E 'Tests run|BUILD|^\[ERROR\]' "$$log" || cat "$$log"; \
	rm -f "$$log"; \
	grep -h "Tests run" $(APP)/*/target/failsafe-reports/*.txt 2>/dev/null || echo "no integration tests ran"; \
	echo "reports in $(APP)/*/target/failsafe-reports/"; \
	exit $$status

capabilities: ## Probe every alive registry library for Solr field availability
	./dev/capabilities.sh

libraries: ## Write a complete library snapshot for a registry-free deployment (needs network)
	./dev/libraries.py

country-codes: ## Regenerate the MARC country-code list from id.loc.gov (needs network) — review the diff
	./dev/country-codes.py

smoke: ## Hit live instances, one library per adapter (needs the gateway running + network)
	IIIF_BASE=$(SMOKE_BASE) ./dev/smoke.sh

viewer-check: viewer-image ## Load the manifests in all three pinned viewers and assert on the DOM
	@# Depends on the image for the same reason viewer-open does: the harness pages live IN it, so
	@# editing tests/viewer/pages and re-running would otherwise assert against the previous build.
	@# Cached, the build is a second; uncached it is the thing you wanted anyway.
	IIIF_BASE=$(SMOKE_BASE) ./dev/viewer-check.sh

# The `up`/`logs` compose lifecycle arrives with deployment. Targets for things that do not exist yet
# are noise.

# ─── Database ─────────────────────────────────────────────────────────────────

viewer-image: ## Build the pinned viewer image (Mirador, Clover, Universal Viewer)
	$(DOCKER) build -t $(VIEWER_IMAGE) -f tests/viewer/Containerfile tests/viewer

viewer-push: ## Push the pinned viewer image (requires REGISTRY=...)
	$(if $(REGISTRY),,$(error REGISTRY is required))
	$(DOCKER) tag $(VIEWER_IMAGE) $(VIEWER_IMAGE_REF):$(VIEWER_VERSION)
	$(DOCKER) tag $(VIEWER_IMAGE) $(VIEWER_IMAGE_REF):latest
	$(DOCKER) push $(VIEWER_IMAGE_REF):$(VIEWER_VERSION)
	$(DOCKER) push $(VIEWER_IMAGE_REF):latest

viewer-build-push: viewer-image viewer-push ## Build then push the pinned viewer image

viewer-open: viewer-image ## Open the manifests in all three viewers, for a person (needs the gateway running)
	@# The SAME image the assertions run against, so what a person reviews and what viewer-check asserts
	@# on are the same build. Serving it from the container rather than python3 -m http.server is what
	@# makes that true — the viewer files live in the image, not on the host.
	@echo "serving tests/viewer on http://localhost:$(VIEWER_PORT) — ctrl-c to stop"
	@echo "the gateway must be running at $(SMOKE_BASE)"
	@command -v xdg-open >/dev/null && (sleep 2; xdg-open http://localhost:$(VIEWER_PORT)/ >/dev/null 2>&1) &
	@$(DOCKER) run --rm -it --network host \
		-v "$(PWD)/tests/viewer:/work:z" \
		$(VIEWER_IMAGE) node /work/serve.mjs $(VIEWER_PORT)

db-up: ## Start a throwaway Postgres for tests
	$(DOCKER) run -d --rm --name $(DB_CONTAINER) \
		-e POSTGRES_DB=$(DB_NAME) -e POSTGRES_USER=$(DB_USER) -e POSTGRES_PASSWORD=$(DB_PASSWORD) \
		-p $(DB_PORT):5432 $(DB_IMAGE)
	@echo "waiting for postgres..."
	@# A real query, not pg_isready. The postgres image bootstraps on a TEMPORARY server that answers
	@# pg_isready and is then shut down to finish initialising — so the next command raced it and got
	@# "the database system is shutting down". Asking for a row is the check that cannot pass early.
	@until $(DOCKER) exec $(DB_CONTAINER) psql -U $(DB_USER) -d $(DB_NAME) -qtAX -c "select 1" >/dev/null 2>&1; do sleep 1; done
	@# A database of its own for the integration tests. They claim from iiif_dimension_job, and a
	@# worker pointed at the same database claims from it too — it wakes on the tests' own NOTIFY,
	@# takes rows they enqueued and fails them, and the concurrency test then reports 58 of 60
	@# claimed as though SKIP LOCKED were broken. Sharing one database made the suite depend on
	@# whether anything else happened to be running.
	@$(DOCKER) exec $(DB_CONTAINER) psql -U $(DB_USER) -d $(DB_NAME) -qtAX \
		-c "SELECT 1 FROM pg_database WHERE datname = '$(DB_TEST_NAME)'" | grep -q 1 || \
		$(DOCKER) exec $(DB_CONTAINER) createdb -U $(DB_USER) $(DB_TEST_NAME)
	@echo "ready on localhost:$(DB_PORT) — $(DB_NAME) to run against, $(DB_TEST_NAME) for tests"

db-down: ## Remove the throwaway Postgres (destroys data)
	-$(DOCKER) rm -f $(DB_CONTAINER)

db-size: ## Cached pages and bytes per page, to keep the storage estimate honest
	@$(DOCKER) exec $(DB_CONTAINER) psql -U $(DB_USER) -d $(DB_NAME) -c \
		"SELECT count(*) AS documents, \
		        pg_size_pretty(pg_total_relation_size('iiif_document_sizes')) AS total \
		 FROM iiif_document_sizes;"

# ─── Build and ship ───────────────────────────────────────────────────────────

build: lib-install ## Build the fat jar and the container image
	$(MVN) -q -DskipTests package -f $(APP_POM)
	$(DOCKER) build -f Containerfile \
		--build-arg SYSTEM_VERSION=$(VERSION) --build-arg SYSTEM_COMMIT=$(COMMIT) \
		-t $(IMAGE_REF):$(VERSION) -t $(IMAGE_REF):latest .

push: ## Push the image (requires REGISTRY=...)
	$(if $(REGISTRY),,$(error REGISTRY is required))
	$(DOCKER) push $(IMAGE_REF):$(VERSION)
	$(DOCKER) push $(IMAGE_REF):latest

build-push: build push ## Build then push

# ─── Release ──────────────────────────────────────────────────────────────────


tag-version: ## Force-retag v$(VERSION) locally and on the remote
	-git tag -d v$(VERSION)
	-git push origin :refs/tags/v$(VERSION)
	git tag v$(VERSION)
	git push origin v$(VERSION)

tag-latest: ## Move the `latest` git tag
	-git tag -d latest
	-git push origin :refs/tags/latest
	git tag latest
	git push origin latest

release: build-push tag-version tag-latest ## Images plus git tags

# ─── Cleanup ──────────────────────────────────────────────────────────────────

clean: ## Remove build output
	$(MVN) -q clean

clean-images: ## Remove built container images
	-$(DOCKER) rmi $(IMAGE_REF):$(VERSION) $(IMAGE_REF):latest

clean-all: clean clean-images db-down ## Remove everything this repo created

.PHONY: help lib-install set-lib-version \
	format format-check lint typecheck version-check config-check check \
	test test-integration capabilities libraries \
	smoke viewer-image viewer-push viewer-build-push viewer-check viewer-open \
	db-up db-down db-size \
	build push build-push set-version tag-version tag-latest release \
	clean clean-images clean-all
