package minibolsa.bench;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkTest {

    @Test
    void percentileUsesTheNearestRank() {
        long[] oneToHundred = LongStream.rangeClosed(1, 100).toArray();

        assertEquals(50, Benchmark.percentile(oneToHundred, 0.50));
        assertEquals(99, Benchmark.percentile(oneToHundred, 0.99));
        assertEquals(7, Benchmark.percentile(new long[] {7}, 0.99));
    }

    @Test
    void medianOfOddAndEvenCounts() {
        List<Benchmark.Result> three = List.of(new Benchmark.Result(30, 0, 0), new Benchmark.Result(10, 0, 0),
                new Benchmark.Result(20, 0, 0));
        List<Benchmark.Result> four = List.of(new Benchmark.Result(40, 0, 0), new Benchmark.Result(10, 0, 0),
                new Benchmark.Result(30, 0, 0), new Benchmark.Result(20, 0, 0));

        assertEquals(20, Benchmark.median(three, Benchmark.Result::throughput));
        assertEquals(25, Benchmark.median(four, Benchmark.Result::throughput));
    }

    /** O "um comando gera o CSV completo", em miniatura: todos os experimentos, poucas ordens. */
    @Test
    @Timeout(60)
    void oneCommandWritesTheWholeCsv(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("benchmark.csv");

        Benchmark.run(new String[] {"--orders", "640", "--repetitions", "1", "--output", csv.toString()});

        List<String> lines = Files.readAllLines(csv, UTF_8);
        assertEquals(Benchmark.HEADER, lines.getFirst());
        assertEquals(1 + 2 * 2 * 5, lines.size()); // cabeçalho + cenários × estratégias × threads
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("single-writer,1,5-ativos,")), lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("global-lock,16,1-ativo,")), lines::toString);
        for (String row : lines.subList(1, lines.size())) {
            String[] fields = row.split(",");
            assertEquals(6, fields.length, row);
            assertTrue(Double.parseDouble(fields[3]) > 0, row);
            assertTrue(Double.parseDouble(fields[4]) <= Double.parseDouble(fields[5]), "p50 > p99: " + row);
        }
    }
}
