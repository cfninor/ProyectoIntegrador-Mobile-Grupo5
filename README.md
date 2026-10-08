# Solventa · App Android

Cliente móvil de Solventa (MISW4501, Grupo 5). Según el caso, la app es el autoservicio del
cliente asegurado en movilidad: onboarding con prueba de vida, cotización y contratación,
reporte de siniestros con cámara, notificaciones y billetera de pólizas con modo offline.

Consume únicamente el **BFF móvil** (conector C1) del repositorio principal del proyecto.

## Stack

| Elemento | Herramienta |
|---|---|
| Plataforma | Android nativo + Kotlin (Kotlin integrado en AGP 9) |
| UI | Vistas XML + ViewBinding + Material 3 |
| Pruebas unitarias | JUnit (cobertura con JaCoCo vía AGP) |
| Pruebas de UI | Espresso |
| Internacionalización | Recursos de Android (`res/values*/strings.xml`) |

| Versión | Valor |
|---|---|
| Android Gradle Plugin | 9.4.0 (Gradle 9.6.0, JDK 17) |
| compileSdk / targetSdk | 37 |
| minSdk | 26 (Android 8.0) |
| Paquete | `co.solventa.app` |

## Requisitos

- Android Studio reciente (compatible con AGP 9.4) y JDK 17.
- SDK de Android API 37 (Android Studio lo ofrece al abrir el proyecto).

## Ambientes (flavors)

| Flavor | Id de la app | BFF móvil |
|---|---|---|
| `dev` | `co.solventa.app.dev` | `http://10.0.2.2:8011`: el `bff-movil` del `docker-compose` del repositorio principal, visto desde el emulador |
| `qa` | `co.solventa.app.qa` | Se define cuando exista el ambiente QA en AWS |
| `prod` | `co.solventa.app` | Se define cuando exista el ambiente PROD en AWS |

El tráfico en claro (HTTP) solo se permite en `dev` y solo hacia el equipo local; los demás
ambientes exigen HTTPS. La app no permite respaldo en la nube ni transferencia de datos entre
dispositivos.

## Comandos

```bash
./gradlew assembleDevDebug                       # APK de desarrollo
./gradlew lintDevDebug                           # Android Lint
./gradlew testDevDebugUnitTest                   # pruebas unitarias (JUnit)
./gradlew createDevDebugUnitTestCoverageReport   # cobertura (app/build/reports/coverage)
./gradlew connectedDevDebugAndroidTest           # pruebas de UI con Espresso (emulador o dispositivo)
```

## Estructura

```
app/src/
├── main/java/co/solventa/app/
│   ├── MainActivity.kt
│   ├── core/          # configuración, cliente de la API y utilidades transversales
│   └── features/      # (por crear) una carpeta por funcionalidad: onboarding, cotizacion, …
├── main/res/          # layouts, textos (strings.xml) y estilos
├── dev/ · qa/         # recursos que cambian por ambiente (nombre de la app, red)
├── test/              # pruebas unitarias JUnit
└── androidTest/       # pruebas de UI Espresso
```

## Internacionalización

Ningún texto visible se escribe en el código ni en los layouts: todos van en
`res/values/strings.xml` (español, idioma por defecto). Cada idioma adicional se agrega como
`res/values-<idioma>/strings.xml`.

## Integración continua

[`ci.yml`](.github/workflows/ci.yml) corre en cada PR y en cada push a `main`: Android Lint,
pruebas unitarias con cobertura y build del APK `dev`. `ci-gate` es el check obligatorio de
`main`. Las pruebas de Espresso necesitan un emulador; se agregan a la CI en un job aparte
cuando existan pantallas que probar.

## Cómo trabajamos

- `main` protegida: todo entra por PR con 1 aprobación y `ci-gate` en verde.
- Ramas cortas desde `main`: `feature/SOLVENTAG5-95-cotizar-movil`.
- Commits y título del PR con la clave de Jira: `feat(cotizacion): cotizar desde el móvil SOLVENTAG5-95`.
- Merge por squash. Release por sprint con tags `v0.1.0`, `v0.2.0` y `v1.0.0`; la firma de
  release usa secretos del ambiente `release` de GitHub, nunca llaves en el repositorio.
