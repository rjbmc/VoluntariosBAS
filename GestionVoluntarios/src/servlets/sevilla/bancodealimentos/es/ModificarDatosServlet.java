package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
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
import util.sevilla.bancodealimentos.es.SharePointUtil;

@WebServlet("/modificar-datos")
public class ModificarDatosServlet extends HttpServlet {
    private static final long serialVersionUID = 3L; // Versión incrementada
    private static final Logger logger = LoggerFactory.getLogger(ModificarDatosServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) {
            sendJsonResponse(response, HttpServletResponse.SC_UNAUTHORIZED, false, "Acceso no autorizado.");
            return;
        }

        String usuario = (String) session.getAttribute("usuario");
        String context = "Usuario: " + usuario;

        // Recoger parámetros
        String nombre = request.getParameter("nombre");
        String apellidos = request.getParameter("apellidos");
        String nuevoEmail = request.getParameter("email");
        String telefono = request.getParameter("telefono");
        String fechaNacimiento = request.getParameter("fechaNacimiento");
        String cp = request.getParameter("cp");
        String tiendaReferenciaStr = request.getParameter("tiendaReferencia");
        String claveActual = request.getParameter("clave_actual");
        String nuevaClave = request.getParameter("nueva_clave");

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            // Obtener datos actuales necesarios para la lógica
            UserInfo userInfo = getUserInfo(conn, usuario);
            if (userInfo == null) {
                sendJsonResponse(response, HttpServletResponse.SC_NOT_FOUND, false, "Usuario no encontrado.");
                return; // No es necesario rollback si no se hizo nada
            }
            
            // 1. Gestionar cambio de contraseña si se ha solicitado
            if (nuevaClave != null && !nuevaClave.isEmpty()) {
                if (!verificarYCambiarClave(conn, usuario, claveActual, nuevaClave)) {
                    conn.rollback();
                    sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "La contraseña actual no es correcta.");
                    return;
                }
            }

            // 2. Actualizar datos personales en la BD
            actualizarDatosPersonales(conn, usuario, nombre, apellidos, telefono, fechaNacimiento, cp, tiendaReferenciaStr);

            // 3. Sincronizar cambios (excepto email) con SharePoint ANTES de commit
            syncSharePoint(conn, userInfo.sqlRowUuid, nombre, apellidos, telefono, fechaNacimiento, cp, tiendaReferenciaStr, usuario);

            // 4. Gestionar cambio de email si se ha solicitado
            boolean emailHaCambiado = nuevoEmail != null && !nuevoEmail.equalsIgnoreCase(userInfo.emailActual);
            if (emailHaCambiado) {
                iniciarCambioDeEmail(request, conn, usuario, nuevoEmail);
            }

            // 5. Si todo ha salido bien, confirmar la transacción
            conn.commit();

            // 6. Enviar respuesta de éxito
            String successMessage = emailHaCambiado ? "¡Datos guardados! Se ha enviado un correo a tu nueva dirección para verificar el cambio." : "¡Datos actualizados correctamente!";
            sendJsonResponse(response, HttpServletResponse.SC_OK, true, successMessage);

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { LogUtil.logException(logger, ex, "CRITICAL: Rollback fallido en modificación de datos", context); }
            LogUtil.logException(logger, e, "Error en el proceso de modificación de datos", context);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error interno al modificar los datos. El problema ha sido registrado.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { LogUtil.logException(logger, e, "Error cerrando conexión en ModificarDatosServlet", context); }
        }
    }

    // --- Métodos Refactorizados ---

    private UserInfo getUserInfo(Connection conn, String usuario) throws SQLException {
        String sql = "SELECT Email, SqlRowUUID FROM voluntarios WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? new UserInfo(rs.getString("Email"), rs.getString("SqlRowUUID")) : null;
            }
        }
    }

    private boolean verificarYCambiarClave(Connection conn, String usuario, String claveActual, String nuevaClave) throws SQLException {
        String sqlSelect = "SELECT Clave FROM voluntarios WHERE Usuario = ?";
        String claveGuardada = null;
        try (PreparedStatement stmt = conn.prepareStatement(sqlSelect)) {
            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) claveGuardada = rs.getString("Clave");
            }
        }

        if (claveGuardada != null && PasswordUtils.checkPassword(claveActual, claveGuardada)) {
            String nuevaClaveHasheada = PasswordUtils.hashPassword(nuevaClave);
            String sqlUpdate = "UPDATE voluntarios SET Clave = ?, notificar = 'S' WHERE Usuario = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlUpdate)) {
                stmt.setString(1, nuevaClaveHasheada); stmt.setString(2, usuario);
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
            stmt.setString(1, nombre); stmt.setString(2, apellidos); stmt.setString(3, telefono);
            stmt.setString(4, fechaNacimiento); stmt.setString(5, cp);
            try { stmt.setInt(6, Integer.parseInt(tiendaReferenciaStr)); } catch (Exception e) { stmt.setNull(6, java.sql.Types.INTEGER); }
            stmt.setString(7, usuario);
            stmt.executeUpdate();
            LogUtil.logOperation(conn, "MODIF_DATA", usuario, "Datos personales actualizados en BD.");
        }
    }

    private void syncSharePoint(Connection conn, String sqlRowUuid, String nombre, String apellidos, String telefono, String fechaNacimiento, String cp, String tiendaReferenciaStr, String usuarioContext) throws Exception {
        if (sqlRowUuid == null) {
            logger.warn("No se puede sincronizar con SharePoint porque el SqlRowUUID del usuario '{}' es nulo.", usuarioContext);
            return; // No lanzar excepción, solo advertir
        }
        Map<String, Object> spData = new HashMap<>();
        spData.put("field_1", nombre); spData.put("field_2", apellidos);
        spData.put("field_7", telefono); spData.put("field_8", fechaNacimiento); spData.put("field_9", cp);
        try { spData.put("field_5", Integer.parseInt(tiendaReferenciaStr)); } catch (Exception e) { /* no añadir si no es válido */ }
        
        SharepointReplicationUtil.replicate(conn, SharePointUtil.SP_SITE_ID_VOLUNTARIOS, "voluntarios", spData, SharepointReplicationUtil.Operation.UPDATE, sqlRowUuid);
        LogUtil.logOperation(conn, "MODIF_DATA_SP", usuarioContext, "Datos personales sincronizados con SharePoint.");
    }
    
    private void iniciarCambioDeEmail(HttpServletRequest request, Connection conn, String usuario, String nuevoEmail) throws SQLException, MessagingException, UnsupportedEncodingException {
        String token = UUID.randomUUID().toString();
        String sql = "UPDATE voluntarios SET nuevo_email = ?, token_cambio_email = ? WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, nuevoEmail); stmt.setString(2, token); stmt.setString(3, usuario);
            stmt.executeUpdate();
            sendEmailConfirmacionCambio(request, nuevoEmail, token, usuario);
            LogUtil.logOperation(conn, "CHANGE_EMAIL_REQ", usuario, "Solicitud de cambio de email a " + nuevoEmail);
        }
    }

    private void sendEmailConfirmacionCambio(HttpServletRequest request, String emailDestino, String token, String usuario) throws MessagingException, UnsupportedEncodingException {
        String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath();
        String link = baseUrl + "/confirmar-cambio-email.html?token=" + token;

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true"); props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", Config.SMTP_HOST); props.put("mail.smtp.port", Config.SMTP_PORT);

        Session mailSession = Session.getInstance(props, new jakarta.mail.Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() { return new PasswordAuthentication(Config.SMTP_USER, Config.SMTP_PASSWORD); }
        });
        
        Message message = new MimeMessage(mailSession);
        message.setFrom(new InternetAddress(Config.SMTP_USER, "Banco de Alimentos de Sevilla"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailDestino));
        message.setSubject("Confirma tu nueva dirección de correo");
        String htmlContent = "<h1>Confirmación de cambio de email</h1><p>Hola, " + usuario + ".</p>" 
            + "<p>Hemos recibido una solicitud para cambiar la dirección de correo de tu cuenta. Por favor, haz clic en el siguiente enlace para confirmar el cambio:</p>"
            + "<p><a href='" + link + "'>Confirmar nueva dirección de email</a></p>"
            + "<p>Si no has solicitado este cambio, puedes ignorar este mensaje.</p>";
        message.setContent(htmlContent, "text/html; charset=utf-8");
        Transport.send(message);
    }

    // --- Clases de Utilidad y Helpers ---

    private static class UserInfo {
        final String emailActual, sqlRowUuid;
        UserInfo(String email, String uuid) { this.emailActual = email; this.sqlRowUuid = uuid; }
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
}
