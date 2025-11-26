package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Period;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.mindrot.jbcrypt.BCrypt;

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

@WebServlet("/nuevo-voluntario")
public class NuevoVoluntarioServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String usuario = request.getParameter("usuario");
        String nombre = request.getParameter("nombre");
        String apellidos = request.getParameter("apellidos");
        String dni = request.getParameter("dni");
        String clave = request.getParameter("clave");
        String email = request.getParameter("email");
        String telefono = request.getParameter("telefono");
        String fechaNacimientoStr = request.getParameter("fechaNacimiento");
        String cp = request.getParameter("cp");
        int tiendaReferencia = Integer.parseInt(request.getParameter("punto_id"));

        JsonObject jsonResponse = new JsonObject();
        
        try {
            LocalDate fechaNacimiento = LocalDate.parse(fechaNacimientoStr);
            if (Period.between(fechaNacimiento, LocalDate.now()).getYears() < 16) {
                jsonResponse.addProperty("success", false);
                jsonResponse.addProperty("message", "Debes tener al menos 16 años para poder registrarte.");
                response.getWriter().write(jsonResponse.toString());
                return;
            }
        } catch (java.time.format.DateTimeParseException e) {
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "El formato de la fecha de nacimiento no es válido.");
            response.getWriter().write(jsonResponse.toString());
            return;
        }
        
        Connection conn = null;
        String sqlRowUuid = null; // REPLICACIÓN SHAREPOINT: Variable para guardar el UUID

        try {
        	conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            String checkSql = "SELECT Usuario, fecha_baja, verificado FROM voluntarios WHERE Usuario = ? OR `DNI NIF` = ?";
            String existingUser = null;
            boolean isInactive = false;
            boolean isVerified = false; 

            try (PreparedStatement psCheck = conn.prepareStatement(checkSql)) {
                psCheck.setString(1, usuario);
                psCheck.setString(2, dni);
                try (ResultSet rs = psCheck.executeQuery()) {
                    if (rs.next()) {
                        existingUser = rs.getString("Usuario");
                        isInactive = rs.getDate("fecha_baja") != null;
                        isVerified = "S".equals(rs.getString("verificado")); 
                    }
                }
            }

            if (existingUser != null && !isInactive && isVerified) {
                jsonResponse.addProperty("success", false);
                jsonResponse.addProperty("message", "El nombre de usuario o el DNI ya están registrados en una cuenta activa y verificada.");
                conn.rollback();
                response.getWriter().write(jsonResponse.toString());
                return;
            }

            String hashedPassword = PasswordUtils.hashPassword(clave);
            String verificationToken = UUID.randomUUID().toString();
            
            if (existingUser != null && !isInactive && !isVerified) {
                String newVerificationToken = UUID.randomUUID().toString();
                String updateUserSql = "UPDATE voluntarios SET Email = ?, token_verificacion = ? WHERE Usuario = ?";
                try (PreparedStatement psUpdate = conn.prepareStatement(updateUserSql)) {
                    psUpdate.setString(1, email);
                    psUpdate.setString(2, newVerificationToken);
                    psUpdate.setString(3, existingUser);
                    psUpdate.executeUpdate();
                }
                
                conn.commit();
                sendVerificationEmail(request, email, existingUser, newVerificationToken); 
                
                jsonResponse.addProperty("success", true);
                jsonResponse.addProperty("message", "Parece que ya estabas registrado pero sin verificar. Hemos actualizado tu email y reenviado el correo de verificación.");
                jsonResponse.addProperty("email", email);
                response.getWriter().write(jsonResponse.toString());
                return; 
            }

            if (existingUser != null && isInactive) {
                // TODO: REPLICACIÓN SHAREPOINT - Manejar la rehabilitación de un usuario. 
                // Esto podría ser un INSERT o un UPDATE en SharePoint dependiendo de si el usuario existía antes.
                // Por ahora, se omite para centrarse en el flujo de nuevo usuario.
                String updateSql = "UPDATE voluntarios SET Nombre = ?, Apellidos = ?, `DNI NIF` = ?, Clave = ?, tiendaReferencia = ?, " +
                                   "Email = ?, telefono = ?, fechaNacimiento = ?, cp = ?, administrador = 'N', " +
                                   "verificado = 'N', token_verificacion = ?, fecha_baja = NULL, notificar = 'S' " +
                                   "WHERE Usuario = ?";
                try (PreparedStatement psUpdate = conn.prepareStatement(updateSql)) {
                    psUpdate.setString(1, nombre);
                    psUpdate.setString(2, apellidos);
                    psUpdate.setString(3, dni);
                    psUpdate.setString(4, hashedPassword);
                    psUpdate.setInt(5, tiendaReferencia);
                    psUpdate.setString(6, email);
                    psUpdate.setString(7, telefono);
                    psUpdate.setDate(8, java.sql.Date.valueOf(fechaNacimientoStr));
                    psUpdate.setString(9, cp);
                    psUpdate.setString(10, verificationToken);
                    psUpdate.setString(11, existingUser);
                    psUpdate.executeUpdate();
                }
                LogUtil.logOperation(conn, "REHABILITACION", usuario, "Se ha rehabilitado una cuenta inactiva.");

            } else {
                // CASO 4: El usuario es completamente nuevo
                sqlRowUuid = SharepointReplicationUtil.generateUuid(); // REPLICACIÓN SHAREPOINT: Generar UUID

                String insertSql = "INSERT INTO voluntarios (Usuario, Nombre, Apellidos, `DNI NIF`, Clave, tiendaReferencia, " +
                                   "Email, telefono, fechaNacimiento, cp, administrador, verificado, token_verificacion, notificar, SqlRowUUID) " +
                                   "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'N', 'N', ?, 'S', ?)"; // REPLICACIÓN SHAREPOINT: Añadida columna y placeholder
                try (PreparedStatement psInsert = conn.prepareStatement(insertSql)) {
                    psInsert.setString(1, usuario);
                    psInsert.setString(2, nombre);
                    psInsert.setString(3, apellidos);
                    psInsert.setString(4, dni);
                    psInsert.setString(5, hashedPassword);
                    psInsert.setInt(6, tiendaReferencia);
                    psInsert.setString(7, email);
                    psInsert.setString(8, telefono);
                    psInsert.setDate(9, java.sql.Date.valueOf(fechaNacimientoStr));
                    psInsert.setString(10, cp);
                    psInsert.setString(11, verificationToken);
                    psInsert.setString(12, sqlRowUuid); // REPLICACIÓN SHAREPOINT: Establecer el UUID
                    psInsert.executeUpdate();
                }
                 LogUtil.logOperation(conn, "ALTA", usuario, "Nuevo voluntario registrado.");
            }

            conn.commit();

            // --- INICIO: REPLICACIÓN A SHAREPOINT ---
            if (sqlRowUuid != null) { // Solo replicar si hemos generado un UUID (caso de nuevo usuario)
                try {
                    Map<String, Object> spData = new HashMap<>();
                    // OJO: Las claves (ej. "Nombre_x0020_voluntario") deben ser los NOMBRES INTERNOS de las columnas en SharePoint.
                    spData.put("field_1", nombre); // Usamos Title como un estándar común para la columna principal.
                    spData.put("field_2", apellidos);
                    spData.put("field_3", dni); // Ejemplo de cómo se codificaría 'DNI NIF'
                    spData.put("field_6", email);
                    spData.put("field_7", telefono);
                    spData.put("field_9", cp);
                    
                    SharepointReplicationUtil.replicate(conn, "voluntarios", spData, SharepointReplicationUtil.Operation.INSERT, sqlRowUuid);

                } catch (Exception e) {
                    // La replicación no debe detener el flujo principal. El error ya se loguea en el replicador.
                    System.err.println("ADVERTENCIA: Fallo al iniciar el proceso de replicación a SharePoint para el UUID: " + sqlRowUuid + ". Causa: " + e.getMessage());
                }
            }
            // --- FIN: REPLICACIÓN A SHAREPOINT ---

            sendVerificationEmail(request, email, usuario, verificationToken);

            jsonResponse.addProperty("success", true);
            jsonResponse.addProperty("message", "¡Registro casi completo! Se ha enviado un correo de verificación a tu email. Por favor, revísalo para activar tu cuenta.");
            jsonResponse.addProperty("email", email); 
            
        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            
            if (e.getErrorCode() == 1062) {
                 jsonResponse.addProperty("success", false);
                 jsonResponse.addProperty("message", "El nombre de usuario, DNI o Email ya están registrados.");
            } else {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                jsonResponse.addProperty("success", false);
                jsonResponse.addProperty("message", "Error de base de datos. Inténtalo más tarde.");
            }
            e.printStackTrace();
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }

        response.getWriter().write(jsonResponse.toString());
    }
    
    private void sendVerificationEmail(HttpServletRequest request, String emailDestino, String usuario, String token){
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();
        String contextPath = request.getContextPath();
        String urlBase = scheme + "://" + serverName + ":" + serverPort + contextPath;
        String verificationLink = urlBase + "/verificar-email.html?token=" + token;
        
        final String username = Config.SMTP_USER;
        final String password = Config.SMTP_PASSWORD;
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", Config.SMTP_HOST);
        props.put("mail.smtp.port", Config.SMTP_PORT);
        Session session = Session.getInstance(props, new jakarta.mail.Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });

        try {
            Message message = new MimeMessage(session);
			message.setFrom(new InternetAddress(username));
			message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailDestino));
		    message.setSubject("Verificacion correo - App VoluntariosBAS - Banco de Alimentos de Sevilla");
            String emailBody = "Se ha recibido una nueva solicitud de incorporación al voluntariado del Banco De Alimentos de Sevilla, con el código de usuario:"+usuario+".<br><br>"
                         + "Si la has realizado tu, por favor, pulsa el siguiente enlace. En caso contrario, no es necesaria ninguna acción.<br>"
                         + "<strong>Enlace de verificación:</strong><br>"
                         + "<a href=\"" + verificationLink + "\">Pulsa aquí para verificar tu cuenta</a>"
                         + "<strong>La dirección de correo desde donde se envía este mensaje es de sólo envío. Por favor, no la uses para responder.</strong><br>";
			message.setContent(emailBody, "text/html; charset=utf-8");
	        Transport.send(message);
		} catch (MessagingException e) {
            System.err.println("ERROR: No se pudo enviar el email de verificación a " + emailDestino + ": " + e.getMessage());
			e.printStackTrace();
		}
    }
}