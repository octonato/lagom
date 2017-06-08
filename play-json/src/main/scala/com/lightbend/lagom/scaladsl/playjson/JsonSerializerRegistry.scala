/*
 * Copyright (C) 2016-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend.lagom.scaladsl.playjson

import akka.actor.ExtendedActorSystem
import akka.actor.setup.ActorSystemSetup
import akka.serialization.{ SerializationSetup, SerializerDetails }
import play.api.libs.json.{ Format, Reads, Writes }

import scala.collection.immutable

/**
 * Create a concrete subclass of this and initialise the actor system either by providing it in your application cake
 * or manually by creating a serialization setup and passing it to the ActorSystem constructor.
 */
abstract class JsonSerializerRegistry {

  private lazy val registry: Map[String, Format[AnyRef]] = {
    serializers.map(entry =>
      (entry.entityClass.getName, entry.format.asInstanceOf[Format[AnyRef]])).toMap
  }

  protected def serializers: immutable.Seq[JsonSerializer[_]]

  /**
   * A set of migrations keyed by the fully classified class name that the migration should be triggered for
   */
  def migrations: Map[String, JsonMigration] = Map.empty

  def writesFor(clazz: Class[_]): Writes[AnyRef] = serializers
    .collectFirst { case serializer if clazz.isAssignableFrom(serializer.entityClass) => serializer.format.asInstanceOf[Writes[AnyRef]] }
    .getOrElse(throw new RuntimeException(s"Missing play-json serializer for [${clazz.getName}], " +
      s"defined are [${registry.keys.mkString(", ")}]"))

  def readsFor(manifest: String): Reads[AnyRef] = registry.getOrElse(
    manifest,
    throw new RuntimeException(s"Missing play-json serializer for [$manifest], " +
      s"defined are [${registry.keys.mkString(", ")}]")
  )

  /**
   * Concatenate the serializers and migrations of this registry with another registry to form a new registry.
   */
  final def ++(other: JsonSerializerRegistry): JsonSerializerRegistry = {
    val self = this
    new JsonSerializerRegistry {
      override def serializers = self.serializers ++ other.serializers
      override def migrations = self.migrations ++ other.migrations
    }
  }
}

object JsonSerializerRegistry {
  /**
   * Create the serializer details for the given serializer registry.
   */
  def serializerDetailsFor(system: ExtendedActorSystem, registry: JsonSerializerRegistry): immutable.Seq[SerializerDetails] = {
    registry.serializers.map { serializer =>
      SerializerDetails(
        s"lagom-play-json.serialization.bindings.${serializer.entityClass.getName}",
        new PlayJsonSerializer(system, writerFor = serializer.entityClass, registry),
        Vector(serializer.entityClass)
      )
    }
  }

  /**
   * Create a serializer setup for the given serializer registry.
   *
   * This is only useful if you only want to register the play-json serializer and its registry, if you want to
   * register other Akka serializers, then you should create your own serialization setup.
   */
  def serializationSetupFor(registry: JsonSerializerRegistry): SerializationSetup = {
    SerializationSetup { system =>
      serializerDetailsFor(system, registry)
    }
  }

  /**
   * Create an actor system setup for the given serializer registry.
   *
   * This is only useful if you don't want to modify the configuration of the actor system in any way, if you do,
   * then you should instead use [[#serializationSetupFor]], and manually create your own [[ActorSystemSetup]] from
   * that.
   */
  def actorSystemSetupFor(registry: JsonSerializerRegistry): ActorSystemSetup = {
    ActorSystemSetup(serializationSetupFor(registry))
  }
}

/**
 * An empty serializer registry.
 */
object EmptyJsonSerializerRegistry extends JsonSerializerRegistry {
  override val serializers = Nil
}

/**
 * This can be used to mark that using a particular set of components requires a JSON serializer registry to be
 * defined.
 *
 * The jsonSerializerFactory is intentionally abstract to force end users to provide one.
 */
trait RequiresJsonSerializerRegistry extends ProvidesJsonSerializerRegistry {
  /**
   * The serializer registry.
   *
   * If no JSON serializers need to be provided, this can simply return [[EmptyJsonSerializerRegistry]].
   */
  def jsonSerializerRegistry: JsonSerializerRegistry

  override def optionalJsonSerializerRegistry: Option[JsonSerializerRegistry] = {
    // If one is already provided for some reason, then concatenate this one with that.
    Some(super.optionalJsonSerializerRegistry.fold(jsonSerializerRegistry)(_ ++ jsonSerializerRegistry))
  }
}

/**
 * This can be used to depend on an optionally provided serializer registry.
 *
 * The purpose of this is to allow Lagom to configure its actor system to use a provided serializer registry if one
 * is provided, but not require one to be provided. It is used in combination with [[RequiresJsonSerializerRegistry]],
 * which for example the persistence components traits can extend to force a user to provide one, and provides that
 * mandatory serializer as the optional serializer provided by this trait.
 */
trait ProvidesJsonSerializerRegistry {

  /**
   * The optionally provided serializer registry.
   *
   * Note that this can also be exploited to allow multiple traits to contribute to the serializer registry rather
   * than provide one, by checking whether the super implementation also provides one, and concatenating with that if
   * it does. To do that, the override must be a def, so that it can be subsequently overridden by other mixed in
   * traits.
   */
  def optionalJsonSerializerRegistry: Option[JsonSerializerRegistry] = None
}
