package com.jiuzhilan.samples

import java.util
import java.util.UUID

import com.jiuzhilan.analytics.Log.Level
import com.jiuzhilan.analytics.messages.{IdentifyMessage, TrackMessage}
import com.jiuzhilan.analytics.{Analytics, Log}

object Test {
  def main(args: Array[String]): Unit = {
    val analytics = Analytics.builder("test_java").endpoint("http://n.com").
      log(new Log() {

        def print(level: Level, format: String, args: AnyRef*): Unit = {
          System.out.println("[" + level + "] " + String.format(format, args:_*))
        }


        def print(level: Log.Level, error: Throwable, format: String, args: AnyRef*): Unit = {
          this.print(level, format, args)
          error.printStackTrace()
        }
      })
      .flushQueueSize(5)
      .retries(1)
      .build


    val properties = new util.LinkedHashMap[String, AnyRef]
    properties.put("title", "Snow Fall")
    properties.put("subtitle", "The Avalance at Tunnel Creek")
    properties.put("author", "John Branch")

    val context = new util.LinkedHashMap[String, AnyRef]
    val contextPage = new util.LinkedHashMap[String, AnyRef]() {}

    context.put("userAgent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_0) AppleWebKit/537.36")
    context.put("ip", "108.0.78.21")
    context.put("page", contextPage)
    analytics.enqueue(
      TrackMessage.builder("Article_Bookmarked")
        .userId("f4ca124298")
        .anonymousId(UUID.randomUUID.toString)
        .properties(properties)
        .context(properties))

    analytics.enqueue(
      IdentifyMessage.builder
        .userId("f4ca124298")
        .anonymousId(UUID.randomUUID.toString)
        .context(properties))

    analytics.flush()
    analytics.shutdown()
  }
}
