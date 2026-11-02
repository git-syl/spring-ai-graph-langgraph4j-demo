package com.example.refunddemo.web;

import org.springframework.boot.test.context.SpringBootTest;

/** Real provider, native tool calling, production Session advisors and embedded HTTP/SSE server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssistantHttpLiveIT extends AssistantHttpContract { }
