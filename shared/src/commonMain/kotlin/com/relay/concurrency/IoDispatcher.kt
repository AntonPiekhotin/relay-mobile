package com.relay.concurrency

import kotlinx.coroutines.CoroutineDispatcher

expect fun ioDispatcher(): CoroutineDispatcher
