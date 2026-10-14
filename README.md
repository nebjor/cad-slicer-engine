# CAD Slicer Engine Service

[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://target.com)
[![Spring Boot 3.x](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)](https://spring.io)
[![License MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> **Executive Summary:**  
> A high-performance, headless microservice that automates 3D CAD file processing (STL/3MF to G-code) for web portals and print farms. Built to decouple heavy CPU computations from API threads, it handles high file traffic seamlessly, protects infrastructure from crash overloads, and powers scalable manufacturing workflows.

---

### Technical Overview

A high-throughput, asynchronous microservice built with **Java 21** and **Spring Boot 3** that offloads heavy 3D CAD slicing operations to a headless Bambu Studio CLI engine via `ProcessBuilder`.

Engineered with a **zero-trust security model** to prevent Denial-of-Cost (DoC) and resource exhaustion. It processes manufacturing files asynchronously using **AMQP event queues**, streams binary assets via direct-to-S3 pre-signed URLs, and emits real-time execution telemetry for KEDA-driven Kubernetes autoscaling.


## Key Capabilities

* **Asynchronous Execution Queue:** Offloads CPU-intensive slicing operations from HTTP threads using RabbitMQ task workers to guarantee sub-millisecond API response times.
* **Direct-to-S3 Processing:** Utilizes S3 pre-signed URLs (compatible with AWS S3 / MinIO) to bypass application server memory limits during large CAD uploads.
* **Headless Native Execution:** Wraps `bambu-studio-cli` inside a headless virtual framebuffer (`xvfb-run`) via Java `ProcessBuilder`.
* **G-Code Telemetry Parsing:** Extracts exact filament consumption (grams), layer timing, and bounding box dimensions from generated G-code outputs.
* **Production Observability:** Exposes custom Spring Boot Actuator metrics for Prometheus scraping and KEDA event-driven Kubernetes autoscaling.

---

## Architectural Workflow

```text
[ Angular Frontend ] ───( Direct Upload )───► [ S3 / MinIO Bucket ]
        │                                             ▲
        │ (1. POST /api/v1/slice-jobs)                │ (2. Fetch File)
        ▼                                             │
[ Slicer REST API ] ───( 3. Publish Event )───► [ RabbitMQ Queue ]
                                                      │
                                                      │ (4. Consume Task)
                                                      ▼
                                           [ Slicer Worker Node ]
                                                      │
                                                      ├── ProcessBuilder (`xvfb-run bambu-studio-cli`)
                                                      └── Parse Metadata ──► [ PostgreSQL ]
