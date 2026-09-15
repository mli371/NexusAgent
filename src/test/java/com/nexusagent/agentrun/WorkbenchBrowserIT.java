package com.nexusagent.agentrun;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Opt-in browser test: requires Docker, Chrome, npm dependencies and a built scripted worker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "nexus.agent.enabled=true", "nexus.agent.worker-mode=scripted",
        "nexus.agent.worker-token=workbench-browser-test-token-32-characters",
        "nexus.agent.event-poll-interval=100ms", "nexus.redis.enabled=false",
        "nexus.embeddings.provider=local", "nexus.embeddings.dimension=384"
})
@Testcontainers
class WorkbenchBrowserIT {
    private static final String WORKER_TOKEN = "workbench-browser-test-token-32-characters";
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container
    static final GenericContainer<?> minio = new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
            .withEnv("MINIO_ROOT_USER", "browser-test")
            .withEnv("MINIO_ROOT_PASSWORD", "browser-test-password")
            .withCommand("server", "/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("nexus.storage.minio.endpoint", () -> "http://%s:%d".formatted(minio.getHost(), minio.getMappedPort(9000)));
        registry.add("nexus.storage.minio.access-key", () -> "browser-test");
        registry.add("nexus.storage.minio.secret-key", () -> "browser-test-password");
        registry.add("nexus.storage.minio.bucket", () -> "browser-fixtures");
    }

    @LocalServerPort int port;
    @Autowired DatabaseClient db;

    @Test
    void browserApprovesChunkAndEmbedAgainstIsolatedInfrastructure(@TempDir Path temporary) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        assertThat(root.resolve("workers/pi-worker/dist/src/main.js")).exists();
        ProcessBuilder workerBuilder = new ProcessBuilder("node", "workers/pi-worker/dist/src/main.js")
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(temporary.resolve("worker.log").toFile());
        workerBuilder.environment().put("NEXUS_AGENT_API_URL", "http://127.0.0.1:" + port);
        workerBuilder.environment().put("NEXUS_AGENT_WORKER_MODE", "scripted");
        workerBuilder.environment().put("NEXUS_AGENT_WORKER_TOKEN", WORKER_TOKEN);
        workerBuilder.environment().put("NEXUS_LLM_API_KEY", "");
        workerBuilder.environment().put("NEXUS_LLM_PROVIDER", "");
        workerBuilder.environment().put("NEXUS_LLM_MODEL", "");
        int browserPort;
        try (ServerSocket socket = new ServerSocket(0)) { browserPort = socket.getLocalPort(); }
        ProcessBuilder browserBuilder = new ProcessBuilder("npm", "--prefix", "frontend", "run", "test:e2e")
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(temporary.resolve("browser.log").toFile());
        browserBuilder.environment().put("NEXUS_WORKBENCH_LIVE", "1");
        browserBuilder.environment().put("NEXUS_FRONTEND_API_TARGET", "http://127.0.0.1:" + port);
        browserBuilder.environment().put("NEXUS_TEST_FRONTEND_PORT", Integer.toString(browserPort));
        Process worker = workerBuilder.start();
        Process browser = null;
        try {
            browser = browserBuilder.start();
            assertThat(browser.waitFor(150, TimeUnit.SECONDS)).as("Browser test timed out").isTrue();
            assertThat(browser.exitValue()).withFailMessage("%s%n%s", Files.readString(temporary.resolve("browser.log")),
                    Files.readString(temporary.resolve("worker.log"))).isZero();
            assertThat(count("SELECT count(*) FROM documents")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM parent_chunks")).isPositive();
            long children = count("SELECT count(*) FROM child_chunks");
            assertThat(children).isPositive();
            assertThat(count("SELECT count(*) FROM child_chunk_embeddings")).isEqualTo(children);
            assertThat(count("SELECT count(*) FROM ingestion_jobs WHERE status='SUCCEEDED'")).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM agent_runs WHERE status='SUCCEEDED'")).isEqualTo(1);
        } finally {
            stop(browser);
            stop(worker);
        }
    }

    private long count(String sql) {
        return db.sql(sql).map(row -> row.get(0, Long.class)).one().block(Duration.ofSeconds(5));
    }

    private void stop(Process process) throws InterruptedException {
        if (process == null) return;
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
