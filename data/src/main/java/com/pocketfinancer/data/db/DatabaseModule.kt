package com.pocketfinancer.data.db

import com.pocketfinancer.data.db.dao.AccountDao
import com.pocketfinancer.data.db.dao.QueuedSmsCandidateDao
import com.pocketfinancer.data.db.dao.SmsProcessingDao
import com.pocketfinancer.data.db.dao.TransactionDao
import com.pocketfinancer.data.db.dao.TransactionRevisionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(factory: AppDatabase.Factory): AppDatabase {
        return factory.create()
    }

    @Provides
    fun provideTransactionDao(db: AppDatabase): TransactionDao {
        return db.transactionDao()
    }

    @Provides
    fun provideAccountDao(db: AppDatabase): AccountDao {
        return db.accountDao()
    }

    @Provides
    fun provideQueuedSmsCandidateDao(db: AppDatabase): QueuedSmsCandidateDao {
        return db.queuedSmsCandidateDao()
    }

    @Provides
    fun provideSmsProcessingDao(db: AppDatabase): SmsProcessingDao {
        return db.smsProcessingDao()
    }

    @Provides
    fun provideTransactionRevisionDao(db: AppDatabase): TransactionRevisionDao {
        return db.transactionRevisionDao()
    }
}
