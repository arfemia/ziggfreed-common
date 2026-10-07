package com.ziggfreed.common.util;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Where a data file the library has moved to another folder is kept this run, moving it there the first
 * time a load finds it only in the old folder. One rule for every store that changed folders, so they
 * cannot drift apart:
 * <ul>
 *   <li>only in the old folder: it moves to the new one, its {@code .bak} with it (a backup the new
 *       folder already holds is never overwritten), with one line in the log naming both folders;</li>
 *   <li>in both: the new one is read, and the old one is left as it was, with one warning;</li>
 *   <li>in neither, or only in the new folder: the new folder is used, and nothing is created;</li>
 *   <li>a move that fails: the file stays where it was and is read and written there this run, with one
 *       warning, so nothing is lost and the next load tries the move again. Never a throw.</li>
 * </ul>
 * The file is the authority, its {@code .bak} only a fallback: the file moves first, and a backup that
 * cannot follow it stays behind with a note on the same line.
 */
public final class DataFileMove {

    /** What a settle found and did. */
    public enum Outcome {
        /** Nothing to move: no old folder, the same folder, neither file, or the file already in the new one. */
        STAYED,
        /** The file moved from the old folder to the new one. */
        MOVED,
        /** Both folders hold the file: the new one is used and the old one left alone. */
        BOTH_FOUND,
        /** The move failed, so the file in the old folder is used this run. */
        MOVE_FAILED
    }

    /**
     * Where the file is kept this run, and what the settle did.
     *
     * @param file    the file to read and write this run
     * @param outcome what the settle found and did
     */
    public record Home(@Nonnull Path file, @Nonnull Outcome outcome) {
    }

    private static final String BACKUP_SUFFIX = ".bak";

    private DataFileMove() {
    }

    /**
     * Where {@code fileName}, which now lives in {@code homeDir} and used to live in {@code oldDir}, is
     * kept this run, moving it on the first load that finds it only in {@code oldDir}. Never throws.
     *
     * @param logTag   the store's own log prefix, e.g. {@code "almanac"}
     * @param homeDir  the folder the file lives in from now on
     * @param oldDir   the folder it used to live in; null when it never lived anywhere else
     * @param fileName the file's name, the same in both folders
     */
    @Nonnull
    public static Home settle(@Nonnull String logTag, @Nonnull Path homeDir, @Nullable Path oldDir,
            @Nonnull String fileName) {
        Path home = homeDir.resolve(fileName);
        if (oldDir == null || sameFolder(homeDir, oldDir)) {
            return new Home(home, Outcome.STAYED);
        }
        Path old = oldDir.resolve(fileName);
        try {
            boolean inHome = Files.exists(home);
            boolean inOld = Files.isRegularFile(old);
            if (inHome && inOld) {
                SafeLog.warn("[" + logTag + "] " + fileName + " is in both " + homeDir + " and " + oldDir + ": the one in "
                        + homeDir + " is read, and the one in " + oldDir + " is left as it was");
                return new Home(home, Outcome.BOTH_FOUND);
            }
            if (inHome || !inOld) {
                return new Home(home, Outcome.STAYED);
            }
            return move(logTag, homeDir, oldDir, fileName);
        } catch (Throwable t) {
            SafeLog.warn("[" + logTag + "] could not look for " + fileName + " in " + homeDir + " and " + oldDir + " ("
                    + t.getMessage() + "), so " + homeDir + " is used");
            return new Home(home, Outcome.STAYED);
        }
    }

    /** Move the file, then its backup; a file that cannot move stays where it was and is used there. */
    @Nonnull
    private static Home move(@Nonnull String logTag, @Nonnull Path homeDir, @Nonnull Path oldDir,
            @Nonnull String fileName) {
        Path home = homeDir.resolve(fileName);
        Path old = oldDir.resolve(fileName);
        try {
            Files.createDirectories(homeDir);
            Files.move(old, home);
        } catch (Throwable t) {
            SafeLog.warn("[" + logTag + "] " + fileName + " could not be moved from " + oldDir + " to " + homeDir + " ("
                    + t.getMessage() + "), so it is read and written in " + oldDir + " this run");
            return new Home(old, Outcome.MOVE_FAILED);
        }
        SafeLog.info("[" + logTag + "] " + fileName + " moved from " + oldDir + " to " + homeDir
                + moveBackup(oldDir.resolve(fileName + BACKUP_SUFFIX), homeDir.resolve(fileName + BACKUP_SUFFIX)));
        return new Home(home, Outcome.MOVED);
    }

    /** Move the backup beside the moved file, never over one already there; the log line's tail. */
    @Nonnull
    private static String moveBackup(@Nonnull Path oldBackup, @Nonnull Path homeBackup) {
        if (!Files.isRegularFile(oldBackup)) {
            return "";
        }
        if (Files.exists(homeBackup)) {
            return "; its " + BACKUP_SUFFIX + " stays in the old folder, since the new one already holds one";
        }
        try {
            Files.move(oldBackup, homeBackup);
            return ", with its " + BACKUP_SUFFIX;
        } catch (Throwable t) {
            return "; its " + BACKUP_SUFFIX + " could not follow (" + t.getMessage() + ") and stays in the old folder";
        }
    }

    private static boolean sameFolder(@Nonnull Path a, @Nonnull Path b) {
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (Throwable t) {
            return a.equals(b);
        }
    }
}
