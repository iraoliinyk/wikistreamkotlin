# Docker Implementation Comparison: Current vs Scratch-Based

## Executive Summary

This document provides a detailed comparison between the **current JVM-based Docker implementation** and the **proposed scratch-based implementation** for the WikiStream Kotlin application.

| Aspect | Current (JVM) | Scratch-based |
|--------|---------------|---------------|
| **Image Size** | ~500 MB | ~100-150 MB |
| **Startup Time** | 2-4 seconds | <100 milliseconds |
| **Build Time** | ~1 minute | ~7-10 minutes |
| **Memory per Instance** | 300-500 MB | 50-100 MB |
| **Build Complexity** | Simple | Complex (GraalVM) |
| **Deployment Readiness** | ✅ Production-ready | ⚠️ Requires testing |
| **Recommended Use** | Development, small scale | Kubernetes, auto-scaling |

---

## Current Implementation (JVM-based)

### Architecture
```
Dockerfile (current)
├─ Builder Stage (eclipse-temurin:24-jdk)
│  ├─ Gradle wrapper + source code
│  ├─ Compile to JAR with bootJar
│  └─ Output: ~25 MB JAR file
│
└─ Runtime Stage (eclipse-temurin:24-jre)
   ├─ Copy JAR from builder
   ├─ Create spring user
   └─ Expose port 7000
```

### Characteristics
```
Base Image:        eclipse-temurin:24-jre (440 MB)
Compiled Artifact: Spring Boot JAR (25 MB)
Total Image:       ~500 MB
Build Time:        ~52 seconds (includes dependency download)
Startup:           2-4 seconds (JVM init + Spring init)
Memory (running):  300-500 MB (heap configurable)
Execution Model:   Bytecode interpretation on JVM
Debug Support:     ✅ Full (debuggers, profilers, etc.)
Shell Access:      ✅ Yes (/bin/sh available)
```

### Dockerfile Content
```dockerfile
FROM eclipse-temurin:24-jdk AS builder
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.jar
COPY gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.properties
COPY build.gradle.kts settings.gradle.kts ./
COPY src src

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon clean bootJar
RUN JAR_PATH=$(find build/libs -maxdepth 1 -type f -name "*-SNAPSHOT.jar" ! -name "*-plain.jar" | head -n 1) \
    && test -n "$JAR_PATH" \
    && cp "$JAR_PATH" app.jar

FROM eclipse-temurin:24-jre
WORKDIR /app

RUN useradd --system --uid 10001 spring
USER spring

COPY --from=builder /workspace/app.jar app.jar

EXPOSE 7000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

### Pros ✅
- Fast build process (~1 minute)
- Mature, well-tested stack
- Easy local development
- Full Java ecosystem support
- Easy debugging and profiling
- No special tooling required (just Docker)
- Supports reflection without configuration
- All libraries work without modification

### Cons ❌
- Large image size (~500 MB)
- Slow startup (2-4 seconds)
- High memory usage (300-500 MB minimum)
- JVM startup overhead
- Contains entire JRE even for small apps
- Larger attack surface

### Build Command
```bash
docker build -t wikistreamkotlin:latest .
```

### Run Command
```bash
docker run --rm -p 7000:7000 --name wikistreamkotlin wikistreamkotlin:latest
```

### When to Use
✅ Development and testing
✅ Small deployments (< 100 instances)
✅ Team familiar with JVM ecosystem
✅ Fast iteration cycles needed
✅ Complex reflection-heavy code
✅ Deployment frequency > build time

---

## Proposed Implementation #1: Native Image (Dynamic Linking)

### Architecture
```
Dockerfile.native
├─ Builder Stage (ghcr.io/graalvm/native-image:24-muslib)
│  ├─ Gradle wrapper + source code
│  ├─ Compile to JAR with bootJar
│  ├─ Extract JAR + libraries
│  ├─ native-image tool creates ELF binary
│  └─ Output: ~50 MB ELF binary
│
└─ Runtime Stage (ubuntu:24.04 - optional base for libs)
   ├─ Copy binary from builder
   ├─ System libraries available
   └─ Expose port 7000
```

### Characteristics
```
Base Image:        ubuntu:24.04 (77 MB) + runtime libs
Compiled Artifact: Native ELF binary (50-80 MB)
Total Image:       ~200-250 MB
Build Time:        ~5-7 minutes
Startup:           500-800 milliseconds
Memory (running):  80-150 MB
Execution Model:   Direct native code execution
Debug Support:     ⚠️ Limited (no debugger)
Shell Access:      ✅ Yes (has /bin/sh)
Reflection Config: ⚠️ Needs configuration
```

### Dockerfile.native Content
```dockerfile
FROM ghcr.io/graalvm/native-image:24-muslib AS builder
# ... build steps (JAR + extract + native-image)

FROM ubuntu:24.04
WORKDIR /app
COPY --from=builder /workspace/app /app/app
EXPOSE 7000
ENTRYPOINT ["/app/app"]
```

### Pros ✅
- Smaller image than JVM (~200 MB)
- Much faster startup (~500 ms)
- Lower memory usage (80-150 MB)
- No JVM overhead
- Slightly smaller attack surface
- Good balance of size and performance

### Cons ❌
- Longer build time (~5-7 minutes)
- Requires GraalVM installation
- Limited debugging capabilities
- Reflection configuration needed
- Build failures harder to diagnose
- Some libraries may be incompatible

### Build Command
```bash
docker build -t wikistreamkotlin:native -f Dockerfile.native .
```

### Run Command
```bash
docker run --rm -p 7001:7000 --name wikistreamkotlin-native wikistreamkotlin:native
```

### When to Use
✅ Medium deployments (100-1000 instances)
✅ Kubernetes with some scaling
✅ Balance between build time and runtime efficiency
✅ Cloud deployments where startup matters somewhat
✅ Team comfortable with native compilation

---

## Proposed Implementation #2: Scratch-Based (Fully Static)

### Architecture
```
Dockerfile.scratch
├─ Builder Stage (ghcr.io/graalvm/native-image:24-muslib)
│  ├─ Gradle wrapper + source code
│  ├─ Compile to JAR with bootJar
│  ├─ Extract JAR + libraries
│  ├─ native-image with --static --libc=musl
│  └─ Output: ~100-150 MB fully static ELF binary
│
└─ Runtime Stage (scratch - completely empty!)
   ├─ Copy ONLY the binary from builder
   ├─ No OS, no libraries, no shell
   └─ Expose port 7000
```

### Characteristics
```
Base Image:        scratch (0 MB - just empty container)
Compiled Artifact: Fully static ELF binary (100-150 MB)
Total Image:       ~100-150 MB
Build Time:        ~7-10 minutes
Startup:           <100 milliseconds
Memory (running):  50-100 MB
Execution Model:   Direct native code (no dependencies)
Debug Support:     ❌ None (no shell, no tools)
Shell Access:      ❌ No (/bin/sh not available)
Reflection Config: ⚠️ Requires careful setup
```

### Dockerfile.scratch Content
```dockerfile
FROM ghcr.io/graalvm/native-image:24-muslib AS builder
# ... build steps (JAR + extract)

RUN native-image \
  --static \
  --libc=musl \
  -H:Name=app \
  ... app

FROM scratch
WORKDIR /app
COPY --from=builder /workspace/app /app/app
EXPOSE 7000
ENTRYPOINT ["/app/app"]
```

### Pros ✅
- **Minimal image size** (~100-150 MB) - 3-5x smaller than JVM
- **Instant startup** (<100 ms) - 20-40x faster than JVM
- **Minimal memory** (50-100 MB) - 5-10x less than JVM
- **Minimal attack surface** - no shell, no OS utilities
- **Cost-effective** - scales to thousands of instances efficiently
- **Ideal for serverless/FaaS** - cold start optimized
- **Security hardened** - reduced CVE exposure

### Cons ❌
- **Very long build time** (~7-10 minutes)
- **No debugging possible** - scratch is completely empty
- **No shell access** - can't troubleshoot inside container
- **No stderr/stdout inspection** - logs must go elsewhere
- **Complex setup** - GraalVM + static linking configuration
- **Limited library support** - many libraries don't work with GraalVM
- **Requires Linux/Docker build** - macOS native builds don't work for Linux containers
- **Reflection handling** - must pre-configure all reflection use
- **Network issues harder to diagnose** - no tools like `curl`, `ping`

### Build Command
```bash
# Must build inside Docker to ensure Linux binary
docker build -t wikistreamkotlin:scratch -f Dockerfile.scratch .
```

### Run Command
```bash
docker run --rm -p 7002:7000 --name wikistreamkotlin-scratch wikistreamkotlin:scratch
```

### When to Use
✅ Large scale deployments (1000+ instances)
✅ Kubernetes clusters with aggressive auto-scaling
✅ Serverless/FaaS platforms (AWS Lambda with Java)
✅ Cost-critical applications
✅ Cold-start sensitive workloads
✅ Extremely resource-constrained environments

### When NOT to Use
❌ Development (too slow to build)
❌ Debugging issues (no shell, no tools)
❌ Fast iteration (build takes 7-10 minutes)
❌ Complex reflection requirements
❌ Needing to shell into running container
❌ Using libraries incompatible with GraalVM

---

## Comparison Table

### Size and Performance
```
                    JVM         Native        Scratch
Image Size          ~500 MB     ~200 MB       ~100-150 MB
Startup Time        2-4 sec     500-800 ms    <100 ms
Memory (idle)       300-500 MB  80-150 MB     50-100 MB
Build Time          ~1 min      ~5-7 min      ~7-10 min
```

### Scaling Implications
```
100 Instances:
                    JVM         Native        Scratch
Total Memory        30-50 GB    8-15 GB       5-10 GB
Total Boot Time     200-400 sec 50-80 sec     8-10 sec
Container Size      50 GB       20 GB         10-15 GB
Download Time       50 GB ÷ 100 Mbps = 67 min
                    20 GB ÷ 100 Mbps = 27 min
                    10 GB ÷ 100 Mbps = 13 min
```

### Capabilities Matrix
```
                    JVM         Native        Scratch
Debugging           ✅ Full     ⚠️ Limited    ❌ None
Shell Access        ✅ Yes      ✅ Yes        ❌ No
Library Support     ✅ Full     ⚠️ Most       ⚠️ Some
Reflection Config   ✅ None     ⚠️ Required   ⚠️ Required
Build Speed         ✅ Fast     ⚠️ Slow       ❌ Very Slow
Startup Speed       ❌ Slow     ✅ Fast       ✅ Instant
Memory Efficient    ❌ No       ✅ Yes        ✅ Very Yes
Cost Efficient      ❌ No       ✅ Yes        ✅ Very Yes
```

---

## Detailed Differences

### Build Process

#### JVM (Current)
```bash
1. Copy Gradle wrapper & source
2. Run gradlew clean bootJar
3. Create runtime container
4. Copy JAR
5. Set entry point

Total Time: ~1 minute
```

#### Scratch (Proposed)
```bash
1. Copy Gradle wrapper & source
2. Run gradlew clean bootJar
3. Extract JAR contents
4. Run GraalVM native-image (minutes 2-10)
   - Static analysis of bytecode
   - Class initialization at build time
   - Linking all dependencies into binary
   - Creating musl-based static executable
5. Create scratch container
6. Copy only the binary (no JVM, no OS)
7. Set entry point

Total Time: ~7-10 minutes
```

### Image Content

#### JVM Container
```
500 MB total
├─ JVM Runtime (300 MB)
│  ├─ JVM executable
│  ├─ JRE libraries
│  ├─ Object files
│  └─ Runtime data
├─ OS Base (140 MB)
│  ├─ glibc
│  ├─ shell
│  ├─ utilities
│  └─ system files
└─ Application (25 MB)
   └─ app.jar (bytecode)
```

#### Scratch Container
```
100-150 MB total
└─ Application Binary (100-150 MB)
   ├─ Spring Framework (compiled)
   ├─ Netty (compiled)
   ├─ Kotlin Runtime (compiled)
   ├─ Jackson (compiled)
   ├─ All dependencies (compiled)
   ├─ musl C library (statically linked)
   └─ GraalVM runtime (minimal)

Everything else: NOT INCLUDED
- No JVM (all compiled away)
- No shell (can't exec into container)
- No system utilities (curl, ping, etc.)
- No package manager
- No extra libraries
```

### Runtime Behavior

#### JVM Startup Sequence
```
1. Docker starts container (0.5s)
2. Java executable loads (0.2s)
3. JVM initialization (0.8s)
4. Class loading (0.6s)
5. Spring Boot startup (0.8s)
6. Ready to accept requests (2.9s total)

Logs during startup:
  ✅ Visible (can see all initialization steps)
  ✅ Debuggable (can attach debugger)
  ✅ Configurable (JVM flags change behavior)
```

#### Scratch Startup Sequence
```
1. Docker starts container (0.5s)
2. Native binary loaded (0.01s)
3. GraalVM RT init (0.01s)
4. Spring Boot startup (0.05s)
5. Ready to accept requests (0.07s total)

Logs during startup:
  ⚠️ Minimal (already compiled away)
  ⚠️ Can't debug (no debugger support)
  ⚠️ Static (all behavior pre-determined)
```

### Troubleshooting

#### JVM Issues
```
Problem: App slow on startup
Solution: 
  ✅ Attach debugger
  ✅ Add JVM profiler flags
  ✅ Check class loading logs
  ✅ Review Spring initialization events
  ✅ Connect to running JVM for heap analysis
```

#### Scratch Issues
```
Problem: App slow on startup
Solution:
  ❌ Can't attach debugger
  ⚠️ Must rebuild with different compiler flags
  ⚠️ Send logs to external system
  ⚠️ Enable verbose native-image flags during build
  ⚠️ Rebuild and redeploy for any investigation
```

---

## Migration Path

### Phase 1: Current State (Today)
```
✅ JVM-based Docker in production
✅ Fast development cycle
✅ All tooling works
✅ No special setup needed
```

### Phase 2: Setup Native Build (Week 1)
```
✅ Add native-image Gradle plugin
✅ Create Dockerfile.native
✅ Test locally with Docker
✅ Validate in CI/CD pipeline
```

### Phase 3: Add Scratch Option (Week 2)
```
✅ Create Dockerfile.scratch
✅ Build in Docker (GraalVM container)
✅ Run performance tests
✅ Document differences
```

### Phase 4: Evaluate in Staging (Week 3)
```
✅ Deploy all three versions to test cluster
✅ Load test each variant
✅ Measure real-world performance
✅ Monitor resource usage
```

### Phase 5: Decide Baseline (Week 4)
```
If small-to-medium scale:
  → Keep JVM as default
  → Offer native as opt-in

If large scale with auto-scaling:
  → Switch to native as default
  → Keep JVM for development
```

---

## Recommendation for This Project

### Current Situation
- **WikiStream** is a **moderate-scale** application
- In-memory statistics storage (not persistent)
- Moderate load expected (not millions of requests)
- Single instance or small cluster likely

### Recommended Approach

1. **Keep Current JVM Implementation** as default
   - Best for development
   - Proven and stable
   - No special setup needed

2. **Prepare Native Image Option** for future
   - Add GraalVM native-image plugin to build.gradle.kts
   - Create Dockerfile.native as alternative
   - Document usage when needed

3. **Skip Scratch for Now**
   - Complexity not justified for current scale
   - Better to focus on features
   - Can revisit if scaling to Kubernetes with 1000+ replicas

### Decision Criteria for Switching

**Switch to Native if:**
```
□ Deploying to Kubernetes with 100+ replicas
□ Using aggressive pod auto-scaling
□ Each 100 MB image size matters (cloud cost)
□ Startup time < 1 second is critical
□ Running on serverless/FaaS platform
```

**Use Scratch Only if:**
```
□ Deploying 1000+ concurrent instances
□ Cost per instance is critical concern
□ Auto-scaling happens frequently (hundreds/day)
□ Can accept longer build times
□ Team comfortable with native image constraints
```

---

## Files Provided

### Documentation
- `DOCKER_SCRATCH_ANALYSIS.md` - Detailed comparison matrix
- `SCRATCH_IMAGE_GUIDE.md` - Complete guide to scratch builds

### Dockerfiles
- `Dockerfile` - Current JVM-based (recommended)
- `Dockerfile.native` - Native image with dynamic linking
- `Dockerfile.scratch` - Scratch-based static binary

### Docker Compose
- `docker-compose.yml` - Current JVM setup
- `docker-compose.multi.yml` - All three variants side-by-side

### Build Configuration
- `build.gradle.kts` - Updated with GraalVM native-image plugin

---

## Quick Reference Commands

### JVM Version (Current - Use This)
```bash
# Build
docker build -t wikistreamkotlin:jvm .

# Run
docker run --rm -p 7000:7000 wikistreamkotlin:jvm

# Compose
docker compose up --build
```

### Native Version (Alternative)
```bash
# Build
docker build -t wikistreamkotlin:native -f Dockerfile.native .

# Run
docker run --rm -p 7001:7000 wikistreamkotlin:native
```

### Scratch Version (Future Option)
```bash
# Build (inside Docker only)
docker build -t wikistreamkotlin:scratch -f Dockerfile.scratch .

# Run
docker run --rm -p 7002:7000 wikistreamkotlin:scratch
```

### Test All Three
```bash
# Build all versions
docker compose -f docker-compose.multi.yml build

# Run all versions
docker compose -f docker-compose.multi.yml up

# Check health
curl http://localhost:7000/v1/status  # JVM
curl http://localhost:7001/v1/status  # Native
curl http://localhost:7002/v1/status  # Scratch
```

---

## Summary

| Aspect | Recommendation |
|--------|---|
| **Use for production today** | JVM (Dockerfile) |
| **Prepare for future** | Native (Dockerfile.native) |
| **Skip for now** | Scratch (Dockerfile.scratch) |
| **Default docker-compose** | JVM (docker-compose.yml) |
| **For comparison testing** | Multi (docker-compose.multi.yml) |

**Conclusion**: Current JVM implementation is appropriate. Keep it as the default while maintaining the option to switch to native images if scale demands it.


