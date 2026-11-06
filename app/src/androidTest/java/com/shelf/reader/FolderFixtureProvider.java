package com.shelf.reader;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Java keeps this test-APK provider independent of the target APK's Kotlin runtime. */
public class FolderFixtureProvider extends DocumentsProvider {
    private static class Doc {
        final String id, parent, name, title;
        Doc(String id, String parent, String name, String title) {
            this.id = id; this.parent = parent; this.name = name; this.title = title;
        }
    }
    private final List<Doc> docs = Arrays.asList(
        new Doc("root", null, "Folder fixture", null), new Doc("a", "root", "Fantasy", null),
        new Doc("b", "root", "Modern", null), new Doc("aa", "a", "Shared", null), new Doc("ba", "b", "Shared", null),
        new Doc("file-root", "root", "direct.epub", "Direct book"), new Doc("file-a", "a", "fantasy.epub", "Fantasy book"),
        new Doc("file-aa", "aa", "nested.epub", "Fantasy nested book"), new Doc("file-ba", "ba", "nested.epub", "Modern nested book")
    );
    private String failing = "";
    private final List<String> queried = new ArrayList<>();
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(new String[] {"root_id", "document_id", "title", "flags"});
        cursor.addRow(new Object[] {"fixture", "root", "Folder fixture", 0});
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) {
        if (id.startsWith("__")) {
            if (id.equals("__reset")) { failing = ""; queried.clear(); }
            if (id.startsWith("__fail_")) { failing = id.substring(7); queried.clear(); }
            MatrixCursor cursor = new MatrixCursor(new String[] {"ids"});
            cursor.addRow(new Object[] {String.join(",", queried)});
            return cursor;
        }
        List<Doc> matches = new ArrayList<>();
        for (Doc doc : docs) if (doc.id.equals(id)) matches.add(doc);
        return cursor(projection, matches);
    }
    @Override public Cursor queryChildDocuments(String parent, String[] projection, String order) throws FileNotFoundException {
        queried.add(parent);
        if (parent.equals(failing)) throw new FileNotFoundException("Fixture disconnected");
        List<Doc> matches = new ArrayList<>();
        for (Doc doc : docs) if (parent.equals(doc.parent)) matches.add(doc);
        return cursor(projection, matches);
    }
    @Override public boolean isChildDocument(String parent, String id) {
        if (id.startsWith("__")) return true;
        for (Doc doc : docs) if (doc.id.equals(id)) return true;
        return false;
    }
    private Cursor cursor(String[] projection, List<Doc> matches) {
        String[] columns = projection != null ? projection : new String[] {Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
        MatrixCursor cursor = new MatrixCursor(columns);
        for (Doc doc : matches) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                switch (columns[i]) {
                    case Document.COLUMN_DOCUMENT_ID: row[i] = doc.id; break;
                    case Document.COLUMN_DISPLAY_NAME: row[i] = doc.name; break;
                    case Document.COLUMN_MIME_TYPE: row[i] = doc.title == null ? Document.MIME_TYPE_DIR : "application/epub+zip"; break;
                    case Document.COLUMN_SIZE: row[i] = doc.title == null ? 0L : epub(doc).length(); break;
                    case Document.COLUMN_LAST_MODIFIED: row[i] = 1000000L; break;
                }
            }
            cursor.addRow(row);
        }
        return cursor;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        for (Doc doc : docs) if (doc.id.equals(id) && doc.title != null)
            return ParcelFileDescriptor.open(epub(doc), ParcelFileDescriptor.MODE_READ_ONLY);
        throw new FileNotFoundException(id);
    }
    private File epub(Doc doc) {
        File file = new File(getContext().getCacheDir(), doc.id + ".epub");
        if (file.exists()) return file;
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("META-INF/container.xml", "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"content.opf\"/></rootfiles></container>");
        entries.put("content.opf", "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>" + doc.title + "</dc:title></metadata><manifest><item id=\"chapter\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"chapter\"/></spine></package>");
        entries.put("chapter.xhtml", "<html><body><h1>Chapter 1</h1><p>Reading text for " + doc.title + ".</p></body></html>");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException error) { throw new IllegalStateException(error); }
        return file;
    }
}
