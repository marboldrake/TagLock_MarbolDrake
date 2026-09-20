# TagLock - Control de Enfoque y Bloqueo Fisico con NFC

TagLock es una aplicacion nativa para Android disenada para eliminar las distracciones digitales y combatir la procrastinacion mediante el uso de llaves fisicas NFC y bloqueo estricto a nivel de sistema operativo.

---

## 1. Descripcion General

A diferencia de los bloqueadores de aplicaciones tradicionales basados unicamente en temporizadores de software que el usuario puede desactivar en momentos de debilidad o impulso, TagLock vincula el estado de concentracion a un objeto fisico del mundo real: una tarjeta, llavero o pegatina NFC. 

Al activar una sesion de bloqueo, las aplicaciones seleccionadas quedan inaccesibles en tiempo real. La unica manera estandar de liberarlas es acercar fisicamente la tarjeta NFC autorizada o cumplir las condiciones estrictas del modo configurado.

---

## 2. Componentes y Funcionamiento Tecnico

### 2.1. Seguridad y Autenticacion NFC
- Validacion Criptografica NDEF: TagLock genera una clave secreta unica (token alfanumerico) que se graba en la memoria NDEF de la tarjeta NFC.
- Validacion por Identificador de Hardware (UID): Adicionalmente, el sistema registra el UID unico de fabrica del chip NFC para evitar clonaciones basicas.
- Modo de Simulacion para Pruebas: Para entornos de desarrollo o dispositivos sin antena NFC física, la aplicacion incluye una alternativa de prueba controlada.

### 2.2. Intercepcion en Tiempo Real (Servicio de Accesibilidad)
- La aplicacion utiliza un servicio de accesibilidad (`AppBlockerAccessibilityService`) que monitorea los eventos de cambio de ventana (`TYPE_WINDOW_STATE_CHANGED`).
- Cuando el usuario intenta abrir una aplicacion catalogada dentro de la lista de bloqueo, el servicio detecta el nombre del paquete en milisegundos e invoca de inmediato la pantalla de bloqueo (`BlockOverlayActivity`).

### 2.3. Pantalla de Bloqueo Inquebrantable (BlockOverlayActivity)
- Se ejecuta en una tarea independiente y prioritaria (`FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP`).
- Anula el boton de retroceso del sistema para evitar que el usuario cierre la pantalla y acceda a la aplicacion prohibida.
- Proporciona un boton de salida segura que redirige directamente a la pantalla de inicio del lanzador de Android (`Intent.CATEGORY_HOME`).

### 2.4. Modos de Bloqueo Disponibles
Al guardar la lista de aplicaciones restringidas, el usuario puede seleccionar entre tres modalidades:

1. Bloqueo por Temporizador:
   - Permite definir duraciones preestablecidas (15, 30 o 60 minutos) o un valor personalizado en minutos.
   - Muestra un reloj visual decreciente en la pantalla de bloqueo.
   - Permite agregar tiempo adicional (+5 minutos) para reforzar la concentracion, pero no permite reducirlo.

2. Bloqueo Indefinido (Solo Tarjeta NFC):
   - No tiene limite de tiempo ni cuenta atras.
   - Las aplicaciones permaneceran inaccesibles hasta que el usuario acerque la tarjeta NFC fisica configurada.

3. Bloqueo Indefinido (Solo Boton de Emergencia):
   - No tiene limite de tiempo ni admite desbloqueo por tarjeta NFC.
   - Requiere mantener presionado de forma continua un boton de rescate durante 30 segundos ininterrumpidos para desbloquear.

### 2.5. Boton de Emergencia de 30 Segundos
- En caso de urgencia real (por ejemplo, si la tarjeta NFC no se encuentra a mano), el sistema ofrece un mecanismo de salida por friccion deliberada.
- El usuario debe mantener el dedo presionado sobre el boton durante 30 segundos completos. Si retira el dedo antes de tiempo, el progreso se reinicia a cero de forma inmediata.

### 2.6. Validacion Obligatoria de Permisos de Android
- Para garantizar la integridad del sistema, TagLock comprueba que los permisos de Accesibilidad y Notificaciones esten activos antes de permitir el inicio de cualquier sesion de bloqueo.
- El asistente de configuracion inicial no permite continuar hasta que ambos permisos hayan sido otorgados.

### 2.7. Notificaciones del Sistema y Acciones Remotas
- Informa el estado del bloqueo en la barra de notificaciones del dispositivo.
- En el modo con temporizador, actualiza el tiempo restante en formato MM:SS y ofrece una accion rapida para extender la sesion (+5 minutos) directamente desde la notificacion.

---

## 3. Easter Egg y Mini-Juego: TagLock Runner

Al pulsar repetidamente cinco veces sobre el titulo de la aplicacion en la pantalla principal, se desbloquea una pantalla secreta interactiva:

- Mini-Juego Estilo Runner: Inspirado en el clasico juego del dinosaurio, donde el personaje protagonista es un candado animado con piernas.
- Mecanica: El candado corre continuamente y debe saltar tocando la pantalla para esquivar obstaculos tematicos de distraccion digital (smartphones con alertas, burbujas de notificaciones y avisos de redes sociales).
- Registro de puntuacion y record historico persistente.
- Informacion y biografia del creador con accesos directos al repositorio oficial.

---

## 4. Requisitos del Sistema

- Sistema Operativo: Android 8.0 (API 26) o superior.
- Hardware Recomendado: Dispositivo con sensor NFC integrado (compatible con etiquetas NFC Forum Tipo 2 o NTAG213/NTAG215/NTAG216).
- Permisos requeridos:
  - android.permission.NFC
  - android.permission.POST_NOTIFICATIONS
  - android.permission.BIND_ACCESSIBILITY_SERVICE

---

## 5. Repositorio y Enlaces

- Repositorio Oficial en GitHub: https://github.com/marboldrake/TagLock_MarbolDrake
- Pagina de Apoyo: https://Ko-fi.com/Marbol077

---

## 6. Creditos

- Autor y Desarrollador: Marbol077
