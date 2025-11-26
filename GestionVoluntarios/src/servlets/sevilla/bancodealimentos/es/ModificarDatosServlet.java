package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.rmi.AccessException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import com.google.gson.JsonObject;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
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

@WebServlet("/modificar-datos")
public class ModificarDatosServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    // ... (doGet permanece igual)

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JsonObject jsonResponse = new JsonObject();
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
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

        Connection conn = null;
        String sqlRowUuid = null; // REPLICACIÓN SHAREPOINT: Variable para el UUID

        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            // REPLICACIÓN SHAREPOINT: Obtener el UUID de la fila ANTES de hacer cambios.
            sqlRowUuid = getSqlRowUuid(conn, usuario);

            String emailActual = getEmailActual(conn, usuario);
            boolean emailHaCambiado = !nuevoEmail.equalsIgnoreCase(emailActual);
            
            if (nuevaClave != null && !nuevaClave.isEmpty()) {
                if (!verificarYCambiarClave(conn, usuario, claveActual, nuevaClave)) {
                    jsonResponse.addProperty("success", false);
                    jsonResponse.addProperty("message", "La contraseña actual no es correcta.");
                    response.getWriter().write(jsonResponse.toString());
                    conn.rollback();
                    return;
                }
            }

            actualizarDatosPersonales(conn, usuario, nombre, apellidos, telefono, fechaNacimiento, cp, tiendaReferenciaStr);

            if (emailHaCambiado) {
                iniciarCambioDeEmail(conn, usuario, nuevoEmail);
                jsonResponse.addProperty("success", true);
                jsonResponse.addProperty("message", "¡Datos guardados! Se ha enviado un correo a tu nueva dirección para verificar el cambio.");
            } else {
                jsonResponse.addProperty("success", true);
                jsonResponse.addProperty("message", "¡Datos actualizados correctamente!");
            }
            
            conn.commit();

            // --- INICIO: REPLICACIÓN A SHAREPOINT ---
            if (sqlRowUuid != null) {
                try {
                    Map<String, Object> spData = new HashMap<>();
                    // OJO: Las claves deben ser los NOMBRES INTERNOS de las columnas en SharePoint.
                    spData.put("field_1", nombre);
                    spData.put("field_2", apellidos);
                    spData.put("field_7", telefono);
                    spData.put("field_9", cp);
                    // No actualizamos el email aquí, eso debería ocurrir cuando el usuario lo verifique.
                    
                    SharepointReplicationUtil.replicate(conn, "voluntarios", spData, SharepointReplicationUtil.Operation.UPDATE, sqlRowUuid);

                } catch (Exception e) {
                    System.err.println("ADVERTENCIA: Fallo al iniciar el proceso de replicación a SharePoint para el UUID: " + sqlRowUuid + ". Causa: " + e.getMessage());
                }
            }
            // --- FIN: REPLICACIÓN A SHAREPOINT ---

        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) {}
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error de base de datos al actualizar.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) {}
        }

        response.getWriter().write(jsonResponse.toString());
    }

    // REPLICACIÓN SHAREPOINT: Nuevo método para obtener el UUID de la fila
    private String getSqlRowUuid(Connection conn, String usuario) throws SQLException {
        String uuid = null;
        String sql = "SELECT SqlRowUUID FROM voluntarios WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    uuid = rs.getString("SqlRowUUID");
                }
            }
        }
        return uuid;
    }

    private String getEmailActual(Connection conn, String usuario) throws SQLException {
        String emailActual = "";
        String sql = "SELECT Email FROM voluntarios WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    emailActual = rs.getString("Email");
                }
            }
        }
        return emailActual;
    }

    private boolean verificarYCambiarClave(Connection conn, String usuario, String claveActual, String nuevaClave) throws SQLException {
        String claveGuardada = "";
        try (PreparedStatement stmt = conn.prepareStatement("SELECT Clave FROM voluntarios WHERE Usuario = ?")) {
            stmt.setString(1, usuario);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) claveGuardada = rs.getString("Clave");
        }

        if (PasswordUtils.checkPassword(claveActual, claveGuardada)) {
            String nuevaClaveHasheada = PasswordUtils.hashPassword(nuevaClave);
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE voluntarios SET Clave = ? WHERE Usuario = ?")) {
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
            stmt.setInt(6, Integer.parseInt(tiendaReferenciaStr));
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
        final String adminEmail = Config.SISTEMAS_EMAIL;
        
    	String link = "http://localhost:8080/VoluntariosBAS/confirmar-cambio-email.html?token=" + token;
        String htmlContent = "Por favor, haz clic en el siguiente enlace para confirmar tu nueva dirección de correo: " + link;

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
        
        Message message = new MimeMessage(mailSession);
        try {
			message.setFrom(new InternetAddress(Config.SMTP_USER));
			message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailDestino));
			message.setSubject("Confirma tu nueva dirección de correo - Voluntarios Banco de Alimentos de Sevilla");
			message.setContent(htmlContent, "text/html; charset=utf-8");
			Transport.send(message);
		} catch (MessagingException e) {
			 try {
				LogUtil.logOperation("CHANGE_EMAIL_CONF", usuario, "Error en envio de la confirmación del cambio de email a " + emailDestino);
			 } catch (ServletException e1) { }
			e.printStackTrace();
		}
    }
}

