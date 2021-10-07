.PHONY: build-mvn
build-mvn: ## Build project and install to you local maven repo
	./mvnw clean install

.PHONY: run-local
run-local: ## Run the app locally (without docker)
	MICRONAUT_CONFIG_FILES=conf/application-local.yml java -Dcom.sun.management.jmxremote -jar  target/maskinporten-guardian-*.jar

.PHONY: release-dryrun
release-dryrun: ## Simulate a release in order to detect any issues
	./mvnw release:prepare release:perform -Darguments="-Dmaven.deploy.skip=true" -DdryRun=true