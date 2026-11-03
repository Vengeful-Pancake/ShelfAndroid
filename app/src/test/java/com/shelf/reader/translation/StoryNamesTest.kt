package com.shelf.reader.translation

import org.junit.Assert.*
import org.junit.Test

class StoryNamesTest {
    @Test fun chaptersAndStvMirrorsShareOneStory() {
        assertEquals(NameStore.storyKey("https://sangtacviet.app/truyen/ciweimao/1/123/1/"),
            NameStore.storyKey("https://sangtacviet.com/truyen/ciweimao/1/123/999/"))
        assertNotEquals(NameStore.storyKey("https://sangtacviet.app/truyen/ciweimao/1/123/1/"),
            NameStore.storyKey("https://sangtacviet.app/truyen/ciweimao/1/456/1/"))
    }
    @Test fun queryBookIdsDoNotLeakAcrossStories() {
        assertEquals(NameStore.storyKey("https://novels.example/read.php?bookid=12&chapter=1"),
            NameStore.storyKey("https://novels.example/read.php?bookid=12&chapter=8"))
        assertNotEquals(NameStore.storyKey("https://novels.example/read.php?bookid=12"),
            NameStore.storyKey("https://novels.example/read.php?bookid=13"))
    }
}
