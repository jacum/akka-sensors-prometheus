# Minimalist Akka Observability

**Non-intrusive native Prometheus collectors for Akka internals, negligible performance overhead, suitable for production use.**

Are you
Running Akka in production and need to see, what happens inside?
Would be nice to use Cinnamon, but can't afford LightBend subscription? Maybe already tried Kamon instrumentation, but is looks fragile and slows your app down?

Then Akka Sensors may be a right choice for you. No bytecode instrumentation, yet a treasure trove for a busy observability engineer.

- Small, but powerful feature set to fine-tune your Akka instance, and to improve overall processing latency
- Sensible metrics 
- Grafana dashboards 

## Features
###  Dispatchers stats
 - total threads (histogram) 
 - busy threads (histogram)
 - runnable queue timing (histogram) 
 - runnable run timing (histogram)
### Thread watcher
 - thread watcher, keeping eye on threads running suspiciously long, and reporting their stacktraces - to help you find blocking code quickly 

### Basic actor
 - number of actors (gauge)
 - receive time (histogram)
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
