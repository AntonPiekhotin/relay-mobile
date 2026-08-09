package com.relay.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

actual fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
