package com.unbrick.data.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.unbrick.data.AppDatabase
import org.junit.After
import org.junit.Before

/**
 * Base class for DAO tests providing in-memory database setup and teardown.
 */
abstract class BaseDaoTest {
    protected lateinit var database: AppDatabase

    @Before
    fun createDb() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        database.close()
    }
}
