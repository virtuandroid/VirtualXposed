package com.virtualxposed.log.server;

import com.virtualxposed.log.client.LogMessage;

interface IVLoggingService {
    void log(in LogMessage message);
}