package com.pocet.screenmonitor.di

import android.content.Context
import android.content.SharedPreferences
import com.pocet.screenmonitor.service.AppsScriptClient
import com.pocet.screenmonitor.service.CaptureScheduler
import com.pocet.screenmonitor.service.OcrProcessor
import com.pocet.screenmonitor.service.TextParser
import com.pocet.screenmonitor.util.BatteryOptimizationUtils
import com.pocet.screenmonitor.util.DateUtils
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOcrProcessor(@ApplicationContext context: Context): OcrProcessor {
        return OcrProcessor(context)
    }

    @Provides
    @Singleton
    fun provideTextParser(): TextParser {
        return TextParser()
    }

    @Provides
    @Singleton
    fun provideDateUtils(): DateUtils {
        return DateUtils()
    }

    @Provides
    @Singleton
    fun provideBatteryOptimizationUtils(@ApplicationContext context: Context): BatteryOptimizationUtils {
        return BatteryOptimizationUtils(context)
    }

    @Provides
    @Singleton
    fun provideCaptureScheduler(@ApplicationContext context: Context): CaptureScheduler {
        return CaptureScheduler(context)
    }

    @Provides
    @Singleton
    fun provideAppsScriptClient(@ApplicationContext context: Context, dateUtils: DateUtils): AppsScriptClient {
        return AppsScriptClient.create(context, dateUtils)
    }
}