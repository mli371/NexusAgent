.PHONY: start-stack test run upload chunk embed retrieval-debug context-debug query query-sse agent-query redis-keys demo agent-worker-test agent-worker agent-run-demo agent-run-events frontend frontend-test frontend-build frontend-browser-test

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

agent-worker-test:
	npm --prefix workers/pi-worker test

agent-worker:
	npm --prefix workers/pi-worker start

agent-run-demo:
	bash scripts/agent-demo.sh $(DOC_ID)

agent-run-events:
	bash scripts/agent-events.sh $(RUN_ID) $(AFTER)

frontend:
	npm --prefix frontend run dev

frontend-test:
	npm --prefix frontend test

frontend-build:
	npm --prefix frontend run build

frontend-browser-test:
	npm --prefix frontend run test:e2e
