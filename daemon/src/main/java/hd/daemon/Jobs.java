package hd.daemon;

import hd.protocol.ErrorCode;
import hd.protocol.Response;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/** Runs work in the background so a request can return before it finishes; results are kept for an hour. */
final class Jobs {

    private static final long KEEP_MILLIS = 60 * 60 * 1000;
    private final ExecutorService workers;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    Jobs(ExecutorService workers) {
        this.workers = workers;
    }

    Job submit(String description, Function<Job, Response> work) {
        expire();
        byte[] b = new byte[3];
        random.nextBytes(b);
        Job job = new Job("j:" + HexFormat.of().formatHex(b), description);
        jobs.put(job.id(), job);
        workers.submit(() -> {
            Response r;
            try {
                r = work.apply(job);
            } catch (Throwable t) {
                r = Errors.toResponse(t);
            }
            job.complete(r);
        });
        return job;
    }

    Job get(String id) {
        Job j = jobs.get(id);
        if (j == null) {
            throw new HdException(ErrorCode.JOB_NOT_FOUND, "no job " + id + " (results are kept for one hour)");
        }
        return j;
    }

    /**
     * Waits for the job; returns its result if it finishes in time, else a "still running" response with a hint.
     */
    Response awaitOrPending(Job job, long waitMillis) {
        Response r = job.await(waitMillis);
        if (r != null) {
            return r;
        }
        return Response.ok(null, job.describe(), null, List.of("hd wait " + job.id()));
    }

    boolean anyRunning() {
        return jobs.values().stream().anyMatch(j -> !j.isDone());
    }

    List<Map<String, Object>> describeAll() {
        return jobs.values().stream().map(Job::describe).toList();
    }

    void cancelAll() {
        jobs.values().forEach(j -> j.cancel().cancel());
    }

    private void expire() {
        long now = System.currentTimeMillis();
        jobs.values().removeIf(j -> j.isDone() && now - j.finishedMillis() > KEEP_MILLIS);
    }
}
