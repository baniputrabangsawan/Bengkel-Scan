package com.fr3ts0n.ecu.gui.androbd.workshop;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisKnowledgeLoader;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisRepository;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Staged workshop archives; legacy database validation remains authoritative. */
final class WorkshopArchive
{
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    private static final int MAX_FILES = 10000;
    private WorkshopArchive() { }

    static File export(WorkshopDatabase database, File target) throws IOException
    {
        File media = new File(database.appContext.getFilesDir(), "diagnosis").getCanonicalFile();
        File extra = extra(database);
        DiagnosisKnowledgeLoader loader = new DiagnosisKnowledgeLoader(database.appContext);
        if (extra.isFile()) DiagnosisKnowledgeLoader.mergeExtra(loader.loadBundled(), text(extra, 2 * 1024 * 1024));
        File snapshot = File.createTempFile("workshop-export-", ".db", database.appContext.getCacheDir());
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("Cannot create backup directory");
        File candidate = File.createTempFile("workshop-archive-", ".zip", parent);
        try
        {
            WorkshopBackup.export(database, snapshot);
            validateMediaReferences(snapshot, media);
            long[] totals = {snapshot.length(), 2};
            measure(media, totals);
            if (extra.isFile()) { totals[0] += extra.length(); totals[1]++; }
            if (totals[0] > MAX_BYTES || totals[1] > MAX_FILES) throw new IOException("Workshop backup is too large");
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(candidate)))
            {
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write("{\"format\":\"bengkel-workshop\",\"version\":1}".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                add(zip, snapshot, "workshop.db");
                if (media.isDirectory()) addDirectory(zip, media, media, "media/");
                if (extra.isFile()) add(zip, extra, "knowledge/extra.json");
            }
            if (!candidate.renameTo(target)) throw new IOException("Cannot replace backup archive");
            return target;
        }
        finally { snapshot.delete(); candidate.delete(); }
    }

    static void restore(WorkshopDatabase database, File source) throws IOException
    {
        File stage = new File(database.appContext.getCacheDir(), "workshop-restore-" + java.util.UUID.randomUUID());
        if (!stage.mkdirs()) throw new IOException("Cannot stage archive");
        File currentMedia = new File(database.appContext.getFilesDir(), "diagnosis");
        File oldMedia = new File(database.appContext.getFilesDir(), "diagnosis-before-" + java.util.UUID.randomUUID());
        File rollback = new File(stage, "rollback.db");
        File currentExtra = extra(database);
        File oldExtra = new File(stage, "old-extra.json");
        boolean mediaMoved = false, installedMedia = false, databaseReplaced = false, hadExtra = currentExtra.isFile();
        boolean preserveStage = false;
        try
        {
            unpack(source, stage);
            File restoredDb = new File(stage, "workshop.db");
            File stagedMedia = new File(stage, "media");
            File stagedExtra = new File(stage, "knowledge/extra.json");
            validateManifest(new File(stage, "manifest.json"));
            if (!restoredDb.isFile()) throw new IOException("Archive has no database");
            WorkshopBackup.validateAndMigrate(database, restoredDb);
            validateMediaReferences(restoredDb, stagedMedia);
            DiagnosisKnowledgeLoader loader = new DiagnosisKnowledgeLoader(database.appContext);
            if (stagedExtra.isFile()) DiagnosisKnowledgeLoader.mergeExtra(loader.loadBundled(), text(stagedExtra, 2 * 1024 * 1024));
            WorkshopBackup.export(database, rollback);
            if (hadExtra) copy(currentExtra, oldExtra);
            if (currentMedia.exists())
            {
                if (!currentMedia.renameTo(oldMedia)) throw new IOException("Cannot preserve previous media");
                mediaMoved = true;
            }
            if (stagedMedia.exists())
            {
                if (!stagedMedia.renameTo(currentMedia)) throw new IOException("Cannot install restored media");
                installedMedia = true;
            }
            WorkshopBackup.restore(database, restoredDb);
            databaseReplaced = true;
            if (stagedExtra.isFile()) atomicCopy(stagedExtra, currentExtra);
            else if (currentExtra.exists() && !currentExtra.delete()) throw new IOException("Cannot replace optional knowledge");
            deleteTree(oldMedia);
        }
        catch (IOException | RuntimeException error)
        {
            try
            {
                if (databaseReplaced) WorkshopBackup.restore(database, rollback);
                if (installedMedia) deleteTree(currentMedia);
                if (mediaMoved && !oldMedia.renameTo(currentMedia)) throw new IOException("Cannot roll back previous media");
                if (hadExtra && oldExtra.isFile()) atomicCopy(oldExtra, currentExtra);
                else if (!hadExtra && currentExtra.exists() && !currentExtra.delete()) throw new IOException("Cannot roll back optional knowledge");
            }
            catch (IOException rollbackError)
            {
                preserveStage = true;
                throw new IOException("Restore rollback failed; preserved recovery files at " + stage.getPath()
                        + " and " + oldMedia.getPath(), rollbackError);
            }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Invalid workshop archive", error);
        }
        finally { if (!preserveStage) deleteTree(stage); }
    }

    private static void validateManifest(File file) throws IOException
    {
        try
        {
            JSONObject manifest = new JSONObject(text(file, 1024));
            if (!"bengkel-workshop".equals(manifest.getString("format")) || manifest.getInt("version") != 1)
                throw new IOException("Unsupported archive format");
        }
        catch (org.json.JSONException e) { throw new IOException("Invalid archive manifest", e); }
    }

    private static void validateMediaReferences(File file, File media) throws IOException
    {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY))
        {
            DiagnosisRepository.validateDatabase(db);
            try (Cursor rows = db.rawQuery("SELECT relative_path FROM diagnosis_photos", null))
            {
                while (rows.moveToNext())
                {
                    File photo = confined(media, rows.getString(0));
                    if (!photo.isFile()) throw new IOException("Archive is missing an attached photo");
                }
            }
        }
        File audio = new File(media, "audio");
        File[] files = audio.listFiles();
        if (files == null) return;
        for (File item : files)
        {
            if (!item.getName().endsWith(".json")) continue;
            try
            {
                JSONObject clip = new JSONObject(text(item, 64 * 1024));
                String path = clip.getString("relative_path");
                if (clip.getInt("schema_version") != 1 || !path.matches("audio/[A-Za-z0-9_-]+\\.[A-Za-z0-9]+")
                        || clip.getString("sound_id").trim().isEmpty() || clip.getString("label").trim().isEmpty()
                        || clip.getLong("case_id") < 0 || clip.getLong("created_at") <= 0)
                    throw new IOException("Invalid recording metadata");
                File recording = confined(media, path);
                String stem = recording.getName().substring(0, recording.getName().lastIndexOf('.'));
                if (!item.getName().equals(stem + ".json") || !recording.isFile() || recording.length() == 0)
                    throw new IOException("Archive is missing an audio recording");
                if ((recording.getName().startsWith("recording_") && clip.getLong("case_id") == 0)
                        || (clip.getLong("case_id") > 0 && clip.getString("cause_name").trim().isEmpty()))
                    throw new IOException("Recording lacks a mechanic case association");
            }
            catch (org.json.JSONException e) { throw new IOException("Invalid recording metadata", e); }
        }
    }

    private static void unpack(File source, File stage) throws IOException
    {
        long total = 0;
        Set<String> names = new HashSet<>();
        byte[] buffer = new byte[16384];
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(source)))
        {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
            {
                String name = entry.getName();
                if (!names.add(name) || names.size() > MAX_FILES || entry.isDirectory()) throw new IOException("Invalid archive entry");
                if (!(name.equals("manifest.json") || name.equals("workshop.db") || name.equals("knowledge/extra.json")
                        || name.startsWith("media/photos/") || name.startsWith("media/audio/"))) throw new IOException("Unexpected archive entry");
                File target = confined(stage, name);
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot extract archive");
                try (OutputStream out = new FileOutputStream(target))
                {
                    int count;
                    while ((count = zip.read(buffer)) != -1)
                    {
                        total += count;
                        if (total > MAX_BYTES) throw new IOException("Archive is too large");
                        out.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void addDirectory(ZipOutputStream zip, File root, File directory, String prefix) throws IOException
    {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot read media directory");
        for (File item : files)
        {
            if (!item.getCanonicalFile().equals(item.getAbsoluteFile())
                    || !item.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("Unsafe media path");
            if (item.isDirectory()) addDirectory(zip, root, item, prefix + item.getName() + "/");
            else add(zip, item, prefix + item.getName());
        }
    }

    private static void measure(File file, long[] totals) throws IOException
    {
        if (!file.exists()) return;
        if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new IOException("Unsafe media path");
        if (!file.isDirectory()) { totals[0] += file.length(); totals[1]++; return; }
        File[] children = file.listFiles();
        if (children == null) throw new IOException("Cannot read media files");
        for (File child : children)
        {
            measure(child, totals);
            if (totals[0] > MAX_BYTES || totals[1] > MAX_FILES) throw new IOException("Workshop backup is too large");
        }
    }

    private static void add(ZipOutputStream zip, File file, String name) throws IOException
    {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(file)) { transfer(in, zip); }
        zip.closeEntry();
    }

    private static File confined(File root, String path) throws IOException
    {
        if (path == null || path.isEmpty() || path.contains("\\") || path.contains("..") || path.indexOf('\0') >= 0 || new File(path).isAbsolute())
            throw new IOException("Unsafe archive path");
        File target = new File(root, path);
        if (!target.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("Unsafe archive path");
        return target;
    }

    private static File extra(WorkshopDatabase database) throws IOException
    {
        File root = database.appContext.getExternalFilesDir(null);
        if (root == null) throw new IOException("External app storage unavailable");
        return new File(root, "diagnosis/extra.json");
    }

    private static String text(File source, int maximum) throws IOException
    {
        try (InputStream in = new FileInputStream(source); ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1)
            {
                if (out.size() + count > maximum) throw new IOException("Metadata too large");
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void atomicCopy(File source, File target) throws IOException
    {
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create knowledge directory");
        File candidate = File.createTempFile("restore-", ".json", parent);
        try
        {
            copy(source, candidate);
            if (!candidate.renameTo(target)) throw new IOException("Cannot replace knowledge");
        }
        finally { candidate.delete(); }
    }

    private static void copy(File source, File target) throws IOException
    {
        try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target))
        {
            transfer(in, out);
            out.getFD().sync();
        }
    }

    private static void transfer(InputStream in, OutputStream out) throws IOException
    {
        byte[] buffer = new byte[16384];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
    }

    private static void deleteTree(File file)
    {
        if (file.isDirectory())
        {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
