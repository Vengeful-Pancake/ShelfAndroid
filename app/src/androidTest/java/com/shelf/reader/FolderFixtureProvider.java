package com.shelf.reader;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Java keeps this test-APK provider independent of the target APK's Kotlin runtime. */
public class FolderFixtureProvider extends DocumentsProvider {
    private static class Doc {
        final String id, parent, name, title;
        Doc(String id, String parent, String name, String title) {
            this.id = id; this.parent = parent; this.name = name; this.title = title;
        }
        boolean isCatalog() { return id.equals("bundle-catalog"); }
        boolean isDirectory() { return title == null && !isCatalog(); }
    }
    private final List<Doc> docs = Arrays.asList(
        new Doc("root", null, "Folder fixture", null), new Doc("a", "root", "Fantasy", null),
        new Doc("bundle-catalog", "root", "book-pairs.json", null),
        new Doc("b", "root", "Modern", null), new Doc("aa", "a", "Shared", null), new Doc("ba", "b", "Shared", null),
        new Doc("file-root", "root", "direct.epub", "Direct book"), new Doc("file-a", "a", "fantasy.epub", "Fantasy book"),
        new Doc("file-aa", "aa", "nested.epub", "Fantasy nested book"), new Doc("file-ba", "ba", "nested.epub", "Modern nested book")
    );
    private String failing = "";
    private final List<String> queried = new ArrayList<>();
    private final AtomicInteger openedBooks = new AtomicInteger();
    private volatile boolean updatedTags;
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(new String[] {"root_id", "document_id", "title", "flags"});
        cursor.addRow(new Object[] {"fixture", "root", "Folder fixture", 0});
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) {
        if (id.startsWith("__")) {
            if (id.equals("__reset")) { failing = ""; queried.clear(); updatedTags = false; openedBooks.set(0); }
            if (id.startsWith("__fail_")) { failing = id.substring(7); queried.clear(); }
            if (id.equals("__edit_catalog")) updatedTags = true;
            MatrixCursor cursor = new MatrixCursor(new String[] {"ids"});
            cursor.addRow(new Object[] {id.equals("__book_opens") ? openedBooks.get() : String.join(",", queried)});
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
                    case Document.COLUMN_MIME_TYPE: row[i] = doc.isDirectory() ? Document.MIME_TYPE_DIR : doc.isCatalog() ? "application/json" : "application/epub+zip"; break;
                    case Document.COLUMN_SIZE: row[i] = doc.title == null ? 0L : epub(doc).length(); break;
                    case Document.COLUMN_LAST_MODIFIED: row[i] = 1000000L; break;
                }
            }
            cursor.addRow(row);
        }
        return cursor;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        for (Doc doc : docs) if (doc.id.equals(id) && !doc.isDirectory()) {
            if (doc.isCatalog()) return ParcelFileDescriptor.open(catalog(), ParcelFileDescriptor.MODE_READ_ONLY);
            openedBooks.incrementAndGet();
            return ParcelFileDescriptor.open(epub(doc), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        throw new FileNotFoundException(id);
    }
    private File catalog() {
        File file = new File(getContext().getCacheDir(), "fixture-book-pairs.json");
        String directTag = updatedTags ? "New journeys" : "Zebra stories";
        String json = "{\"schema_version\":1,\"books\":["
            + "{\"files\":{\"vi_epub\":[\"direct.epub\"]},\"tag_classification\":{\"tags\":[{\"name\":\"" + directTag + "\"}]}},"
            + "{\"files\":{\"zh_epub\":[\"Fantasy/Shared/nested.epub\"]},\"tag_classification\":{\"tags\":[{\"name\":\"After dark\"}]}}]}";
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) { throw new IllegalStateException(error); }
        return file;
    }
    private File epub(Doc doc) {
        File file = new File(getContext().getCacheDir(), doc.id + "-overview.epub");
        if (file.exists()) return file;
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("META-INF/container.xml", "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"content.opf\"/></rootfiles></container>");
        boolean featured = doc.id.equals("file-root");
        String metadata = featured
            ? "<dc:subject>Travel</dc:subject><dc:description>&lt;p&gt;A journey that begins at the library.&lt;/p&gt;&lt;p&gt;A story told in two chapters.&lt;/p&gt;</dc:description>"
            : doc.id.equals("file-a") ? "<dc:subject>Adventure</dc:subject>" : "";
        String extraManifest = featured
            ? "<item id=\"next\" href=\"chapter2.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/><item id=\"cover\" href=\"cover.png\" media-type=\"image/png\" properties=\"cover-image\"/>"
            : "";
        entries.put("content.opf", "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>" + doc.title + "</dc:title>" + metadata + "</metadata><manifest><item id=\"chapter\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/>" + extraManifest + "</manifest><spine><itemref idref=\"chapter\"/>" + (featured ? "<itemref idref=\"next\"/>" : "") + "</spine></package>");
        entries.put("chapter.xhtml", "<html><body><h1>Chapter 1</h1><p>Reading text for " + doc.title + ".</p></body></html>");
        if (featured) {
            entries.put("chapter2.xhtml", "<html><body><h1 id='second.stop'>Chapter 2</h1><p>An afternoon in the old library.</p></body></html>");
            entries.put("nav.xhtml", "<html><body><nav><ol><li><a href='chapter.xhtml'>Chapter 1</a></li><li><a href='chapter2.xhtml#second.stop'>Chapter 2</a></li></ol></nav></body></html>");
        }
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            if (featured) {
                Bitmap bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(0xFFB04576);
                ByteArrayOutputStream image = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, image);
                bitmap.recycle();
                zip.putNextEntry(new ZipEntry("cover.png"));
                zip.write(image.toByteArray());
                zip.closeEntry();
            }
        } catch (IOException error) { throw new IllegalStateException(error); }
        return file;
    }
}
