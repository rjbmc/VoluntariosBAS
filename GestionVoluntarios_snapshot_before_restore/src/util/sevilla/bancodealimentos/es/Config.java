// Paquete: util.sevilla.bancodealimentos.es
package util.sevilla.bancodealimentos.es;

/**
 * Clase centralizada para almacenar las variables de configuraciÃ³n de la aplicaciÃ³n.
 */
public final class Config {
    // --- CONFIGURACIÃ“N DEL SERVIDOR DE CORREO (SMTP) ---
    public static final String SMTP_HOST = "smtp.gmail.com";
    public static final String SMTP_PORT = "587";
    public static final String SMTP_USER = "grandesrecogidas@gmail.com"; // Reemplazar
    public static final String SMTP_PASSWORD = "cwpz qwsl yddc lyyd"; // Reemplazar
    public static final String SISTEMAS_EMAIL = "Sistemas@fundacionbas.org"; 
    public static final String ROBERTO_EMAIL = "roberto.medina.cervera@gmail.com"; 
    
    /**
     * Constructor privado para evitar que esta clase de utilidad sea instanciada.
     */
    private Config() {
        // Esta clase no debe ser instanciada.
    }
}
