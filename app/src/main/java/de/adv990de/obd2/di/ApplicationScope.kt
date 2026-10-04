package de.adv990de.obd2.di

import javax.inject.Qualifier

/**
 * Marks the app-wide [kotlinx.coroutines.CoroutineScope] (see `AppModule.provideApplicationScope`):
 * for work that must outlive any single screen or ViewModel — e.g. a deferred delete that is still
 * inside its undo window when the user leaves the screen (`UndoableDeletion`). `viewModelScope`
 * stays the right choice for everything that belongs to a screen.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
