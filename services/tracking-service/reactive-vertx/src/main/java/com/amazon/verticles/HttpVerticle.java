/*
 * Copyright 2010-2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.amazon.verticles;

import com.amazon.util.Constants;
import com.amazon.vo.TrackingMessage;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.util.Iterator;
import java.util.UUID;

public class HttpVerticle extends AbstractVerticle {

    private EventBus eb;
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpVerticle.class);

    @Override
    public void start() {

        this.eb = vertx.eventBus();

        Router router = Router.router(vertx);

        router.route().handler(BodyHandler.create());
        router.get("/event/:eventID").handler(this::handleTrackingEvent);
        router.get("/cache/fill").handler(this::fillCacheWithData);
        router.get("/cache/purge").handler(this::purgeCache);
        router.get("/health/check").handler(this::checkHealth);

        HttpServerOptions httpServerOptions = new HttpServerOptions();
        httpServerOptions.setCompressionSupported(true);

        HttpServer httpServer = vertx.createHttpServer(httpServerOptions);
        httpServer.requestHandler(router::accept).listen(8080);
    }

    private void checkHealth(final RoutingContext routingContext) {
        HttpServerResponse response = routingContext.request().response();
        response.setStatusCode(200);
        response.putHeader("content-type", "application/json");
        response.end();
    }

    private void purgeCache(final RoutingContext routingContext) {
        eb.send(Constants.REDIS_PURGE_EVENTBUS_ADDRESS, "");
        eb.send(Constants.CACHE_PURGE_EVENTBUS_ADDRESS, "");

        HttpServerResponse response = routingContext.request().response();
        response.setStatusCode(200);
        response.putHeader("content-type", "application/json");
        response.end();
    }

    private void fillCacheWithData(final RoutingContext routingContext) {
        LOGGER.info("Filling caches with data ... ");
        LOGGER.debug("Reading JSON-data");

        FileSystem fs = vertx.fileSystem();
        fs.readFile("data.json", res -> {
           if (res.succeeded()) {
                Buffer buf = res.result();
                JsonArray jsonArray = buf.toJsonArray();
               for (Object aJsonArray : jsonArray) {
                   JsonObject obj = (JsonObject) aJsonArray;
                   LOGGER.debug("Sending message to cache-verticles: " + obj);
                   eb.send(Constants.CACHE_STORE_EVENTBUS_ADDRESS, obj);
                   eb.send(Constants.REDIS_STORE_EVENTBUS_ADDRESS, obj);
               }
           } else {
               LOGGER.info(res.cause());
           }

            HttpServerResponse response = routingContext.request().response();
            response.putHeader("content-type", "application/json");
            response.end();
        });
    }

    private void handleTrackingEvent(final RoutingContext routingContext) {

        String userAgent = routingContext.request().getHeader("User-Agent");
        String eventID = routingContext.request().getParam("eventID");

        UUID uuid = UUID.randomUUID();
        TrackingMessage trackingMessage = new TrackingMessage();
        trackingMessage.setMessageId(uuid.toString());
        trackingMessage.setProgramId(eventID);

        JsonObject message = JsonObject.mapFrom(trackingMessage);

        HttpServerResponse response = routingContext.response();
        response.putHeader("content-type", "application/json");
        if (null == eventID) {
            sendError(400, response);
        } else {
            eb.send(Constants.CACHE_EVENTBUS_ADDRESS, message, res -> {
                if (res.succeeded()) {
                    JsonObject result = (JsonObject)res.result().body();
                    if (result.isEmpty()) {
                        response.setStatusCode(404).end(Json.encode("ProgramId not found"));
                    } else {

                        TrackingMessage tmpMsg = Json.decodeValue(result.encode(), TrackingMessage.class);
                        tmpMsg.setUserAgent(userAgent);

                        String enrichedData = Json.encode(tmpMsg);

                        eb.send(Constants.KINESIS_EVENTBUS_ADDRESS, enrichedData);
                        response.setStatusCode(200).end(enrichedData);
                    }
                } else {
                    response.setStatusCode(500).end(res.cause().getMessage());
                }
            });
        }
    }

    private void sendError(int statusCode, HttpServerResponse response) {
        response.setStatusCode(statusCode).end();
    }

}
