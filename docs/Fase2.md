# Fase 2 — App Quarkus + Herramientas básicas de diagnóstico
## Kafka Diagnostic Agent

> **Objetivo**: Desplegar la app Quarkus en OCP con el agente LangChain4j funcional,  
> los tools básicos de Kubernetes/OCP y Strimzi report, y la Web UI v1.  
> Sin RAG todavía — el agente responde solo con datos reales del clúster.

---

## Prerrequisitos

- Fase 1 completada y validada
- Repositorio en GitHub: https://github.com/emunozd/kafka-diag-agent
- `oc` CLI autenticado en el namespace `kafka-diag-agent`

---

## Paso 1 — Build de la app en OCP via S2I

El BuildConfig ya está en el Helm chart. Si usaste Helm, salta al paso 2.
Si no, aplícalo manualmente:

```bash
oc apply -f - <<'EOF'
apiVersion: image.openshift.io/v1
kind: ImageStream
metadata:
  name: kafka-diag-agent
  namespace: kafka-diag-agent
---
apiVersion: build.openshift.io/v1
kind: BuildConfig
metadata:
  name: kafka-diag-agent
  namespace: kafka-diag-agent
spec:
  source:
    type: Git
    git:
      uri: https://github.com/emunozd/kafka-diag-agent
      ref: main
    contextDir: quarkus
  strategy:
    type: Source
    sourceStrategy:
      from:
        kind: ImageStreamTag
        namespace: openshift
        name: java:latest
      env:
        - name: MAVEN_ARGS_APPEND
          value: "-Dquarkus.package.type=uber-jar"
  output:
    to:
      kind: ImageStreamTag
      name: kafka-diag-agent:latest
  triggers:
    - type: ConfigChange
EOF
```

Iniciar el build y monitorear:
```bash
oc start-build kafka-diag-agent -n kafka-diag-agent --follow
```

> El primer build tarda 3-5 minutos descargando dependencias Maven.
> Los builds siguientes usan caché y son más rápidos.

---

## Paso 2 — Desplegar la app

```bash
oc apply -f - <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kafka-diag-app
  namespace: kafka-diag-agent
spec:
  replicas: 1
  selector:
    matchLabels:
      app: kafka-diag-app
  template:
    metadata:
      labels:
        app: kafka-diag-app
    spec:
      serviceAccountName: kafka-diag-sa
      containers:
        - name: kafka-diag-app
          image: image-registry.openshift-image-registry.svc:5000/kafka-diag-agent/kafka-diag-agent:latest
          ports:
            - containerPort: 8080
          env:
            - name: APP_NAMESPACE
              valueFrom:
                fieldRef:
                  fieldPath: metadata.namespace
            - name: LLM_BASE_URL
              value: "http://qwen3-8b-llm-predictor.kafka-diag-agent.svc.cluster.local:8000/v1"
            - name: LLM_API_KEY
              value: "none"
            - name: LLM_MODEL_NAME
              value: "/mnt/models"
            - name: EMBED_BASE_URL
              value: "http://nomic-embed-svc.kafka-diag-agent.svc.cluster.local:8000/v1"
            - name: EMBED_MODEL_NAME
              value: "/mnt/models"
            - name: CHROMA_BASE_URL
              value: "http://chromadb.kafka-diag-agent.svc.cluster.local:8000"
            - name: PDF_BASE_PATH
              value: "/pdfdata"
            - name: RH_OFFLINE_TOKEN
              valueFrom:
                secretKeyRef:
                  name: rh-api-credentials
                  key: RH_OFFLINE_TOKEN
                  optional: true
          volumeMounts:
            - name: pdfs
              mountPath: /pdfdata
              readOnly: true
          resources:
            limits:
              memory: 1Gi
              cpu: "1"
            requests:
              memory: 512Mi
              cpu: "500m"
      volumes:
        - name: pdfs
          persistentVolumeClaim:
            claimName: pvc-pdf-knowledge
---
apiVersion: v1
kind: Service
metadata:
  name: kafka-diag-app
  namespace: kafka-diag-agent
spec:
  selector:
    app: kafka-diag-app
  ports:
    - port: 8080
      targetPort: 8080
---
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: kafka-diag-app
  namespace: kafka-diag-agent
spec:
  to:
    kind: Service
    name: kafka-diag-app
  port:
    targetPort: 8080
  tls:
    termination: edge
    insecureEdgeTerminationPolicy: Redirect
EOF
```

Monitorear:
```bash
oc get pods -n kafka-diag-agent -l app=kafka-diag-app -w
```

Obtener la URL de la app:
```bash
oc get route kafka-diag-app -n kafka-diag-agent -o jsonpath='{.spec.host}'
```

---

## Paso 3 — Verificar health

```bash
APP_URL=$(oc get route kafka-diag-app -n kafka-diag-agent \
  -o jsonpath='{.spec.host}')

curl -s https://${APP_URL}/api/health
# Esperado: {"status":"ok","agent":"kafka-diag-agent"}
```

---

## Paso 4 — Prueba de diagnóstico básico

```bash
APP_URL=$(oc get route kafka-diag-app -n kafka-diag-agent \
  -o jsonpath='{.spec.host}')

# Prueba 1: resumen de arquitectura
curl -s https://${APP_URL}/api/diagnose \
  -H "Content-Type: application/json" \
  -d '{"question":"give me a brief status of the current cluster","namespace":"kafka-diag-agent"}' \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['answer'])"

# Prueba 2: override de namespace
curl -s https://${APP_URL}/api/diagnose \
  -H "Content-Type: application/json" \
  -d '{"question":"list all kafka topics","namespace":"my-kafka-ns"}' \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['answer'])"
```

---

## Paso 5 — Web UI

Abrir en el navegador:
```
https://<APP_URL>/advisor.html
```

---

## Paso 6 — Redesplegar tras cambios en el código

```bash
# Push al repo y hacer rebuild
git push origin main
oc start-build kafka-diag-agent -n kafka-diag-agent --follow

# Cuando el build termine, el deployment se actualiza automáticamente
# por el trigger de ImageStream
```

---

## Notas importantes de la Fase 2

### /no_think forzado en el system prompt
El system prompt incluye `/no_think` al inicio — esto desactiva el modo
reasoning de Qwen3 que consume tokens innecesarios con bloques `<think>`.
Adicionalmente, `DiagnosticResource.java` hace strip de cualquier bloque
`<think>...</think>` residual antes de retornar la respuesta.

### Tool calling y Qwen3
Los flags `--enable-auto-tool-choice` y `--tool-call-parser=hermes` en el
ServingRuntime son obligatorios para que Qwen3 use los `@Tool` de LangChain4j.

### Namespace handling
- Si el usuario no especifica namespace, se usa `APP_NAMESPACE` (el namespace del pod).
- Si escribe "analyze my kafka cluster in namespace my-ns", el agente extrae
  el namespace del texto y lo pasa a todos los tool calls automáticamente.
- El namespace se antepone a la pregunta: `[namespace: my-ns] pregunta...`

### RAG, upload y KCS
En esta fase son placeholders — retornan mensajes informativos pero no bloquean el agente.
Se implementan en Fases 3, 4 y 5 respectivamente.
