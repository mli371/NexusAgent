.PHONY: start-stack test run upload chunk embed retrieval-debug context-debug query query-sse agent-query redis-keys demo

start-stack:
	docker compose up -d

test:
	mvn test

run:
	mvn spring-boot:run

upload:
	scripts/demo.sh upload

chunk:
	scripts/demo.sh chunk

embed:
	scripts/demo.sh embed

retrieval-debug:
	scripts/demo.sh retrieval-debug

context-debug:
	scripts/demo.sh context-debug

query:
	scripts/demo.sh query

query-sse:
	scripts/demo.sh query-sse

agent-query:
	scripts/demo.sh agent-query

redis-keys:
	scripts/demo.sh redis-keys

demo:
	scripts/demo.sh all
