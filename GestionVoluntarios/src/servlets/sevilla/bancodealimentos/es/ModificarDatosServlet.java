package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.PasswordUtils;
import util.sevilla.bancodealimentos.es.SharepointReplicationUtil;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/modificar-datos")
public class ModificarDatosServlet extends HttpServlet {
    private static final long serialVersionUID = 2L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(ModificarDatosServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null) {
            logger.warn("Intento de modificación de datos sin sesión activa. IP: {}", request.getRemoteAddr());
            sendJsonResponse(response, HttpServletResponse.SC_UNAUTHORIZED, false, "Acceso no autorizado.");
            return;
        }

        String usuario = (String) session.getAttribute("usuario");
        
        String nombre = request.getParameter("nombre");
        String apellidos = request.getParameter("apellidos");
        String nuevoEmail = request.getParameter("email");
        String telefono = request.getParameter("telefono");
        String fechaNacimiento = request.getParameter("fechaNacimiento");
        String cp = request.getParameter("cp");
        String tiendaReferenciaStr = request.getParameter("tiendaReferencia");
        String claveActual = request.getParameter("clave_actual");
        String nuevaClave = request.getParameter("nueva_clave");

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false); // Inicio transacción

            try {
                String sqlRowUuid = getSqlRowUuid(conn, usuario);
                String emailActual = getEmailActual(conn, usuario);
                boolean emailHaCambiado = nuevoEmail != null && !nuevoEmail.equalsIgnoreCase(emailActual);
                
                // 1. Cambio de contraseña (si se solicita)
                if (nuevaClave != null && !nuevaClave.isEmpty()) {
                    if (!verificarYCambiarClave(conn, usuario, claveActual, nuevaClave)) {
                        conn.rollback();
                        logger.warn("Intento fallido de cambio de clave para usuario {}. Clave actual incorrecta.", usuario);
                        sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "La contraseña actual no es correcta.");
                        return;
                    }
                }

                // 2. Actualización de datos personales
                actualizarDatosPersonales(conn, usuario, nombre, apellidos, telefono, fechaNacimiento, cp, tiendaReferenciaStr);

                // 3. Gestión de cambio de email (requiere confirmación por correo)
                if (emailHaCambiado) {
                    logger.info("Usuario {} ha solicitado cambio de email. De {} a {}", usuario, emailActual, nuevoEmail);
                    iniciarCambioDeEmail(conn, usuario, nuevoEmail);
                    sendJsonResponse(response, HttpServletResponse.SC_OK, true, "¡Datos guardados! Se ha enviado un correo a tu nueva dirección para verificar el cambio.");
                } else {
                    sendJsonResponse(response, HttpServletResponse.SC_OK, true, "¡Datos actualizados correctamente!");
                }
                
                conn.commit();
                logger.info("Datos personales actualizados correctamente para {}", usuario);

                // 4. Replicación a SharePoint (después del commit local)
                if (sqlRowUuid != null) {
                    try {
                        Map<String, Object> spData = new HashMap<>();
                        spData.put("field_1", nombre);
                        spData.put("field_2", apellidos);
                        if (tiendaReferenciaStr != null && !tiendaReferenciaStr.isEmpty()) { 
                            try {
                                spData.put("field_5", Integer.parseInt(tiendaReferenciaStr)); 
                            } catch(NumberFormatException e) { /* Ignorar */ }
                        }
                        spData.put("field_7", telefono);
                        spData.put("field_8", fechaNacimiento);
                        spData.put("field_9", cp);
                        
                        // Nota: El email NO se actualiza en SP hasta que se confirme
                        
                        SharepointReplicationUtil.replicate(conn, SharepointUtil.SP_SITE_ID_VOLUNTARIOS, "voluntarios", spData, SharepointReplicationUtil.Operation.UPDATE, sqlRowUuid);

                    } catch (Exception e) {
                        logger.error("Fallo al replicar a SharePoint para el usuario {} (UUID: {})", usuario, sqlRowUuid, e);
                        // No hacemos rollback porque el cambio local es válido
                    }
                }

            } catch (SQLException e) {
                if (conn != null) try { conn.rollback(); } catch (SQLException ex) { logger.warn("Rollback fallido", ex); }
                logger.error("Error SQL al actualizar datos de {}", usuario, e);
                sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error de base de datos al actualizar.");
            }
        } catch (SQLException e) {
            logger.error("Error de conexión a base de datos", e);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error al conectar con la base de datos.");
        }
    }

    private void sendJsonResponse(HttpServletResponse response, int statusCode, boolean success, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(statusCode);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            Map<String, Object> jsonResponse = new HashMap<>();
            jsonResponse.put("success", success);
            jsonResponse.put("message", message);
            mapper.writeValue(response.getWriter(), jsonResponse);
        }
    }

    private String getSqlRowUuid(Connection conn, String usuario) throws SQLException {
        String sql = "SELECT SqlRowUUID FROM voluntarios WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("SqlRowUUID") : null;
            }
        }
    }

    private String getEmailActual(Connection conn, String usuario) throws SQLException {
        String sql = "SELECT Email FROM voluntarios WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("Email") : "";
            }
        }
    }

    private boolean verificarYCambiarClave(Connection conn, String usuario, String claveActual, String nuevaClave) throws SQLException {
        String claveGuardada = "";
        String sqlSelect = "SELECT Clave FROM voluntarios WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sqlSelect)) {
            stmt.setString(1, usuario);
            try(ResultSet rs = stmt.executeQuery()){
                 if (rs.next()) claveGuardada = rs.getString("Clave");
            }
        }

        if (claveGuardada != null && !claveGuardada.isEmpty() && PasswordUtils.checkPassword(claveActual, claveGuardada)) {
            String nuevaClaveHasheada = PasswordUtils.hashPassword(nuevaClave);
            String sqlUpdate = "UPDATE voluntarios SET Clave = ? WHERE Usuario = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlUpdate)) {
                stmt.setString(1, nuevaClaveHasheada);
                stmt.setString(2, usuario);
                stmt.executeUpdate();
                LogUtil.logOperation(conn, "MODIF_PASS", usuario, "Contraseña actualizada por el propio usuario.");
                return true;
            }
        }
        return false;
    }
    
    private void actualizarDatosPersonales(Connection conn, String usuario, String nombre, String apellidos, String telefono, String fechaNacimiento, String cp, String tiendaReferenciaStr) throws SQLException {
        String sql = "UPDATE voluntarios SET Nombre = ?, Apellidos = ?, telefono = ?, fechaNacimiento = ?, cp = ?, tiendaReferencia = ?, notificar = 'S' WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, nombre);
            stmt.setString(2, apellidos);
            stmt.setString(3, telefono);
            stmt.setString(4, fechaNacimiento);
            stmt.setString(5, cp);
            if (tiendaReferenciaStr != null && !tiendaReferenciaStr.isEmpty()) {
                try {
                    stmt.setInt(6, Integer.parseInt(tiendaReferenciaStr));
                } catch (NumberFormatException e) {
                    stmt.setNull(6, java.sql.Types.INTEGER);
                }
            } else {
                stmt.setNull(6, java.sql.Types.INTEGER);
            }
            stmt.setString(7, usuario);
            stmt.executeUpdate();
            LogUtil.logOperation(conn, "MODIF", usuario, "Datos personales actualizados.");
        }
    }
    
    private void iniciarCambioDeEmail(Connection conn, String usuario, String nuevoEmail) throws SQLException {
        String token = UUID.randomUUID().toString();
        String sql = "UPDATE voluntarios SET nuevo_email = ?, token_cambio_email = ? WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, nuevoEmail);
            stmt.setString(2, token);
            stmt.setString(3, usuario);
            stmt.executeUpdate();
            sendEmailConfirmacionCambio(nuevoEmail, token, usuario);
            LogUtil.logOperation(conn, "CHANGE_EMAIL_REQ", usuario, "Solicitud de cambio de email a " + nuevoEmail);
        }
    }

    private void sendEmailConfirmacionCambio(String emailDestino, String token, String usuario) {
        final String username = Config.SMTP_USER;
        final String password = Config.SMTP_PASSWORD;
        
        String link = "http://localhost:8080/VoluntariosBAS/confirmar-cambio-email.html?token=" + token;
        String htmlContent = "Por favor, haz clic en el siguiente enlace para confirmar tu nueva dirección de correo: <a href='" + link + "'>Confirmar email</a>";

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", Config.SMTP_HOST);
        props.put("mail.smtp.port", Config.SMTP_PORT);

        Session mailSession = Session.getInstance(props, new jakarta.mail.Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });
        
        try {
            Message message = new MimeMessage(mailSession);
            message.setFrom(new InternetAddress(Config.SMTP_USER));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailDestino));
            message.setSubject("Confirma tu nueva dirección de correo - Voluntarios Banco de Alimentos de Sevilla");
            message.setContent(htmlContent, "text/html; charset=utf-8");
            Transport.send(message);
            
            logger.debug("Correo de confirmación de email enviado a {}", emailDestino);
        } catch (MessagingException e) {
             logger.error("Error al enviar email de confirmación a {}", emailDestino, e);
             // No lanzamos la excepción para no abortar el proceso principal, pero queda registrado
        }
    }
}