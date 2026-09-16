package com.zillit.zillitapp.feature.email.ui

import android.content.Context
import com.zillit.zillitapp.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two words a mail row needs that come from nowhere but the app itself.
 *
 * A row is built in a view model, which has no composition to read string resources from,
 * so both the list and search had their own hardcoded copies of "(No subject)" and "Draft" —
 * untranslated, and disagreeing with the capitalisation used elsewhere on screen.
 */
@Singleton
class EmailRowLabels @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val noSubject: String get() = context.getString(R.string.email_no_subject_row)
    val draft: String get() = context.getString(R.string.email_draft_sender)
}
