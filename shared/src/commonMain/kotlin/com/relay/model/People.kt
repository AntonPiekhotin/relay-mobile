package com.relay.model

data class UserSummary(
    val id: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val avatarUrl: String?
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
