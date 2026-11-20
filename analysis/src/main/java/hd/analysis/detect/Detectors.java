package hd.analysis.detect;

import hd.engine.api.EngineException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/** Registry and runner for detectors. */
public final class Detectors {

    /** A detector that failed: reported instead of aborting the whole run. */
    public record Failure(String detector, String error) {}

    public record Report(List<String> ran, List<Finding> findings, List<Failure> failures) {}

    private Detectors() {}

    public static List<Detector> all() {
        List<Detector> list = new ArrayList<>();
        ServiceLoader.load(Detector.class, Detectors.class.getClassLoader()).forEach(list::add);
        list.sort(Comparator.comparing(Detector::id));
        return list;
    }

    /** @param only detector ids to run; empty = all default-enabled ones; containing "all" = every detector */
    public static Report run(DetectContext ctx, Collection<String> only) {
        List<Detector> selected = new ArrayList<>();
        List<Detector> all = all();
        for (String id : only) {
            if (!id.equals("all") && all.stream().noneMatch(d -> d.id().equals(id))) {
                throw new EngineException(EngineException.Code.INVALID_ARGUMENT, "unknown detector '" + id + "', known: "
                        + all.stream().map(Detector::id).toList());
            }
        }
        for (Detector d : all) {
            if (only.contains("all") || (only.isEmpty() ? d.defaultEnabled() : only.contains(d.id()))) {
                selected.add(d);
            }
        }
        List<Finding> findings = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            Detector d = selected.get(i);
            ctx.cancel().check();
            int step = i;
            DetectContext sub = new DetectContext(ctx.heap(), ctx.cancel(),
                    (phase, f) -> ctx.progress().update(d.id() + ": " + phase,
                            (step + Math.max(0, f)) / selected.size()));
            try {
                findings.addAll(d.run(sub));
            } catch (EngineException e) {
                if (e.code() == EngineException.Code.CANCELLED) throw e;
                failures.add(new Failure(d.id(), e.getMessage()));
            } catch (RuntimeException e) {
                failures.add(new Failure(d.id(), e.toString()));
            }
        }
        findings.sort(Comparator.comparing(Finding::severity)
                .thenComparing(f -> -f.evidence().size().bytes()));
        return new Report(selected.stream().map(Detector::id).toList(), findings, failures);
    }
}
