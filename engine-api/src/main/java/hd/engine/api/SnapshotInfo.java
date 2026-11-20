package hd.engine.api;

import java.nio.file.Path;

/**
 * @param dump the dump file
 * @param engine engine id that opened it
 * @param objectCount number of (reachable unless configured otherwise) objects
 * @param classCount number of classes
 * @param usedHeap sum of shallow sizes of all objects, in bytes
 * @param identifierSize pointer size in the dump (4 or 8)
 * @param compressedOops whether compressed oops were in use, null if unknown
 * @param jvmInfo JVM description if the dump has it, else null
 * @param creationEpochMillis dump creation time, or -1 if unknown
 */
public record SnapshotInfo(Path dump, String engine, long objectCount, long classCount, long usedHeap,
                           int identifierSize, Boolean compressedOops, String jvmInfo, long creationEpochMillis) {
}
