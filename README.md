# Minimalist Akka Observability

**Non-intrusive native Prometheus collectors for Akka internals, negligible performance overhead, suitable for production use.**

Are you planning to run Akka in production full-throttle, and want to see what happens inside? 

Would be nice to use Cinnamon, but LightBend subscription is out of reach? 

Maybe already tried Kamon instrumentation, but is looks fragile and slows your app down, especially when running full-throttle?

Then Akka Sensors may be the right choice for you: Free as in MIT license, and no heavy bytecode instrumentation either, yet a treasure trove for a busy observability engineer.

- Small, but powerful feature set to make internals of your Akka visible, for performance tuning
- Sensible metrics in native Prometheus collectors
- Example pre-configured Akka cluster node with Cassandra persistence
- Some Grafana dashboards included

## Features

###  Dispatchers 
 - total threads (histogram) 
 - busy threads (histogram)
 - runnable queue timing (histogram) 
 - runnable run timing (histogram)

### Thread watcher
 - thread watcher, keeping eye on threads running suspiciously long, and reporting their stacktraces - to help you find blocking code quickly 

### Basic actor
 - number of actors (gauge)
 - receive function run time (histogram)
 - actor activity time (histogram)

### Persistent actor
 - recovery time (histogram)
 - persist time (histogram)
 - recovery failures (counter)
 - persist failures (counter)

### Cluster
 - cluster events, per type (counter)

### Cassandra
Instrumented Cassandra session provider, leveraging Cassandra client metrics collection.

## Usage
```

```
