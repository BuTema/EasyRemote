package com.artem.easyremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    private val sha = "691c08fc5caf56d53fa665de4f1f18975e33bfdc"

    private val release = """
        {"tag_name":"latest","target_commitish":"$sha","published_at":"2026-11-16T21:40:27Z",
         "html_url":"https://github.com/BuTema/EasyRemote/releases/tag/latest",
         "body":"Automatic build of $sha (run 27).\n\n**Phone:** install it",
         "assets":[
           {"name":"EasyRemote-debug.apk","browser_download_url":"https://example/debug.apk"},
           {"name":"easyremote.flatpak","browser_download_url":"https://example/f.flatpak"},
           {"name":"EasyRemote.apk","browser_download_url":"https://example/release.apk"}]}
    """.trimIndent()

    @Test fun releaseApkIsChosenForReleaseBuilds() {
        val i = parseLatestRelease(release, debuggable = false)
        assertNotNull(i)
        assertEquals("https://example/release.apk", i!!.apkUrl)
        assertEquals(sha, i.sha)
    }

    @Test fun debugApkIsChosenForDebuggableBuilds() {
        assertEquals("https://example/debug.apk", parseLatestRelease(release, debuggable = true)!!.apkUrl)
    }

    @Test fun missingApkFallsBackToTheReleasePage() {
        val i = parseLatestRelease("""{"target_commitish":"$sha","html_url":"https://x/y","assets":[]}""", false)
        assertEquals("https://x/y", i!!.apkUrl)
    }

    @Test fun answerWithoutCommitIsRejected() {
        assertNull(parseLatestRelease("""{"assets":[]}""", false))
    }

    @Test fun sameBuildAcceptsAbbreviatedIds() {
        assertTrue(sameBuild(sha, sha))
        assertTrue(sameBuild(sha.take(7), sha))
        assertTrue(sameBuild(sha, sha.take(7)))
        assertFalse(sameBuild("0000000", sha))
        assertFalse(sameBuild("", sha))
        assertFalse(sameBuild(sha, ""))
    }

    @Test fun runNumberIsReadFromTheNotes() {
        assertEquals(27, parseLatestRelease(release, false)!!.number)
        assertNull(parseLatestRelease("""{"target_commitish":"$sha","assets":[]}""", false)!!.number)
    }

    @Test fun newerMeansAHigherBuildNumber() {
        val info = parseLatestRelease(release, false)!!
        assertTrue(isNewer(info, 26, "0000000"))
        assertFalse(isNewer(info, 27, "0000000")) // same number: not newer, whatever the commit says
        assertFalse(isNewer(info, 30, "0000000")) // this build is ahead of the release
        // no number in the notes: different commit counts as an update
        val noNumber = info.copy(number = null)
        assertTrue(isNewer(noNumber, 1, "0000000"))
        assertFalse(isNewer(noNumber, 1, sha.take(7)))
    }
}
