package com.relay.model

data class UserSummary(
    val id: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val avatarUrl: String?
) {
    val displayName: String get() = "$firstName $lastName".trim().ifBlank { email }
}

data class UserProfile(
    val user: UserSummary,
    val createdAtMillis: Long?
)

data class Contact(
    val user: UserSummary,
    val addedAtMillis: Long?
)

data class UserSearchResult(
    val user: UserSummary,
    val isContact: Boolean
)

data class SearchPage(
    val results: List<UserSearchResult>,
    val page: Int,
    val hasNext: Boolean
)
