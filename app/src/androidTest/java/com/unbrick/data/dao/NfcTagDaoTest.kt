package com.unbrick.data.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unbrick.data.model.NfcTagInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NfcTagDaoTest : BaseDaoTest() {

    private val nfcTagDao get() = database.nfcTagDao()

    @Test
    fun insertTag_canRetrieveIt() = runTest {
        val tag = NfcTagInfo(tagId = "ABC123", name = "My Tag")
        nfcTagDao.insert(tag)

        val tags = nfcTagDao.getAllTags().first()
        assertEquals(1, tags.size)
        assertEquals("ABC123", tags[0].tagId)
        assertEquals("My Tag", tags[0].name)
    }

    @Test
    fun getAllTags_returnsEmptyListWhenNoTags() = runTest {
        val tags = nfcTagDao.getAllTags().first()
        assertTrue(tags.isEmpty())
    }

    @Test
    fun getAllTags_flowEmitsUpdates() = runTest {
        val initial = nfcTagDao.getAllTags().first()
        assertEquals(0, initial.size)

        nfcTagDao.insert(NfcTagInfo(tagId = "TAG1", name = "Tag 1"))

        val updated = nfcTagDao.getAllTags().first()
        assertEquals(1, updated.size)
    }

    @Test
    fun getPrimaryTag_returnsFirstTag() = runTest {
        nfcTagDao.insert(NfcTagInfo(tagId = "TAG1", name = "First"))
        nfcTagDao.insert(NfcTagInfo(tagId = "TAG2", name = "Second"))

        val primary = nfcTagDao.getPrimaryTag()
        assertNotNull(primary)
        // Should return first inserted (LIMIT 1)
        assertEquals("TAG1", primary?.tagId)
    }

    @Test
    fun getPrimaryTag_returnsNullWhenNoTags() = runTest {
        val primary = nfcTagDao.getPrimaryTag()
        assertNull(primary)
    }

    @Test
    fun isTagRegistered_returnsTrueForExistingTag() = runTest {
        nfcTagDao.insert(NfcTagInfo(tagId = "ABC123", name = "Test"))

        val result = nfcTagDao.isTagRegistered("ABC123")
        assertTrue(result)
    }

    @Test
    fun isTagRegistered_returnsFalseForNonexistentTag() = runTest {
        nfcTagDao.insert(NfcTagInfo(tagId = "ABC123", name = "Test"))

        val result = nfcTagDao.isTagRegistered("XYZ789")
        assertFalse(result)
    }

    @Test
    fun isTagRegistered_returnsFalseWhenNoTags() = runTest {
        val result = nfcTagDao.isTagRegistered("ABC123")
        assertFalse(result)
    }

    @Test
    fun getTagCount_returnsCorrectCount() = runTest {
        assertEquals(0, nfcTagDao.getTagCount())

        nfcTagDao.insert(NfcTagInfo(tagId = "TAG1", name = "Tag 1"))
        assertEquals(1, nfcTagDao.getTagCount())

        nfcTagDao.insert(NfcTagInfo(tagId = "TAG2", name = "Tag 2"))
        assertEquals(2, nfcTagDao.getTagCount())
    }

    @Test
    fun delete_removesTag() = runTest {
        val tag = NfcTagInfo(tagId = "ABC123", name = "Test")
        nfcTagDao.insert(tag)
        assertEquals(1, nfcTagDao.getTagCount())

        nfcTagDao.delete(tag)

        assertEquals(0, nfcTagDao.getTagCount())
        assertFalse(nfcTagDao.isTagRegistered("ABC123"))
    }

    @Test
    fun deleteAll_removesAllTags() = runTest {
        nfcTagDao.insert(NfcTagInfo(tagId = "TAG1", name = "Tag 1"))
        nfcTagDao.insert(NfcTagInfo(tagId = "TAG2", name = "Tag 2"))
        nfcTagDao.insert(NfcTagInfo(tagId = "TAG3", name = "Tag 3"))
        assertEquals(3, nfcTagDao.getTagCount())

        nfcTagDao.deleteAll()

        assertEquals(0, nfcTagDao.getTagCount())
    }

    @Test
    fun insert_replacesExistingWithSameTagId() = runTest {
        nfcTagDao.insert(NfcTagInfo(tagId = "ABC123", name = "Original"))
        nfcTagDao.insert(NfcTagInfo(tagId = "ABC123", name = "Updated"))

        val tags = nfcTagDao.getAllTags().first()
        assertEquals(1, tags.size)
        assertEquals("Updated", tags[0].name)
    }
}
