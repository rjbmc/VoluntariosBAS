package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.FieldValueSet;

import jakarta.mail.Authenticator;
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
import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.PasswordUtils;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/nuevo-voluntario")
public class NuevoVoluntarioServlet extends HttpServlet {
    private static final long serialVersionUID = 3L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(NuevoVoluntarioServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();
    
    private static final String SP_LIST_NAME = "Voluntarios";

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        // Recogida de parámetros
        String usuario = request.getParameter("usuario");
        String nombre = request.getParameter("nombre");
        String apellidos = request.getParameter("apellidos");
        String dni = request.getParameter("dni");
        String clave = request.getParameter("clave");
        String email = request.getParameter("email");
        String telefono = request.getParameter("telefono");
        String fechaNacimientoStr = request.getParameter("fechaNacimiento");
        String cp = request.getParameter("cp");
        String puntoIdStr = request.getParameter("punto_id");
        int tiendaReferencia = (puntoIdStr != null && !puntoIdStr.isEmpty()) ? Integer.parseInt(puntoIdStr) : 0;

        // Validación de edad
        try {
            LocalDate fechaNacimiento = LocalDate.parse(fechaNacimientoStr);
            if (Period.between(fechaNacimiento, LocalDate.now()).getYears() < 16) {
                logger.warn("Intento de registro de menor de 16 años. Fecha nacimiento: {}", fechaNacimientoStr);
                sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Debes tener al menos 16 años para poder registrarte.");
                return;
            }
        } catch (java.time.format.DateTimeParseException e) {
            sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "El formato de la fecha de nacimiento no es válido.");
            return;
        }
        
        Connection conn = null;
        String sqlRowUuid = null;
        boolean isReactivation = false;

        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            // 1. Comprobar si el usuario ya existe (por Usuario o DNI)
            String checkSql = "SELECT Usuario, fecha_baja, verificado, SqlRowUUID FROM voluntarios WHERE Usuario = ? OR `DNI NIF` = ?";
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
                        sqlRowUuid = rs.getString("SqlRowUUID");
                    }
                }
            }

            // Si existe, está activo y verificado -> Conflicto
            if (existingUser != null && !isInactive && isVerified) {
                logger.warn("Intento de registro duplicado. Usuario: {}, DNI: {}", usuario, dni);
                sendJsonResponse(response, HttpServletResponse.SC_CONFLICT, false, "El nombre de usuario o el DNI ya están registrados en una cuenta activa y verificada.");
                conn.rollback();
                return;
            }

            String hashedPassword = PasswordUtils.hashPassword(clave);
            String verificationToken = UUID.randomUUID().toString();
            
            // Si existe pero está INACTIVO (baja previa) -> REACTIVACIÓN
            if (existingUser != null && isInactive) {
                isReactivation = true;
                logger.info("Reactivando cuenta previamente dada de baja: {}", existingUser);
                
                String updateSql = "UPDATE voluntarios SET Nombre=?, Apellidos=?, `DNI NIF`=?, Clave=?, Email=?, telefono=?, fechaNacimiento=?, cp=?, tiendaReferencia=?, verificado='N', token_verificacion=?, fecha_baja=NULL, notificar='S' WHERE Usuario=?";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setString(1, nombre);
                    ps.setString(2, apellidos);
                    ps.setString(3, dni);
                    ps.setString(4, hashedPassword);
                    ps.setString(5, email);
                    ps.setString(6, telefono);
                    ps.setString(7, fechaNacimientoStr);
                    ps.setString(8, cp);
                    ps.setInt(9, tiendaReferencia);
                    ps.setString(10, verificationToken);
                    ps.setString(11, existingUser); // Usamos el usuario original recuperado
                    ps.executeUpdate();
                }
                // Si reactivamos, usamos el UUID que ya tenía
            } 
            // Si NO existe -> INSERTAR NUEVO
            else if (existingUser == null) {
                sqlRowUuid = UUID.randomUUID().toString();
                logger.info("Registrando nuevo voluntario: {}", usuario);
                
                String insertSql = "INSERT INTO voluntarios (Usuario, Nombre, Apellidos, `DNI NIF`, Clave, Email, telefono, fechaNacimiento, cp, tiendaReferencia, verificado, token_verificacion, SqlRowUUID, notificar) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'N', ?, ?, 'S')";
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, usuario);
                    ps.setString(2, nombre);
                    ps.setString(3, apellidos);
                    ps.setString(4, dni);
                    ps.setString(5, hashedPassword);
                    ps.setString(6, email);
                    ps.setString(7, telefono);
                    ps.setString(8, fechaNacimientoStr);
                    ps.setString(9, cp);
                    ps.setInt(10, tiendaReferencia);
                    ps.setString(11, verificationToken);
                    ps.setString(12, sqlRowUuid);
                    ps.executeUpdate();
                }
            }
            // Si existe pero NO está verificado -> Actualizar datos y token (reintento de registro)
            else {
                logger.info("Actualizando registro incompleto (no verificado) para: {}", existingUser);
                String updateSql = "UPDATE voluntarios SET Nombre=?, Apellidos=?, `DNI NIF`=?, Clave=?, Email=?, telefono=?, fechaNacimiento=?, cp=?, tiendaReferencia=?, token_verificacion=?, notificar='S' WHERE Usuario=?";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setString(1, nombre);
                    ps.setString(2, apellidos);
                    ps.setString(3, dni);
                    ps.setString(4, hashedPassword);
                    ps.setString(5, email);
                    ps.setString(6, telefono);
                    ps.setString(7, fechaNacimientoStr);
                    ps.setString(8, cp);
                    ps.setInt(9, tiendaReferencia);
                    ps.setString(10, verificationToken);
                    ps.setString(11, existingUser);
                    ps.executeUpdate();
                }
                // Mantenemos el UUID existente
            }

            conn.commit();
            LogUtil.logOperation(conn, "ALTA", usuario, isReactivation ? "Reactivación de cuenta" : "Nuevo registro de voluntario");

            // 2. Replicación a SharePoint (no bloqueante)
            if (sqlRowUuid != null) {
                try {
                    Map<String, Object> spData = new HashMap<>();
                    spData.put("field_1", nombre);
                    spData.put("field_2", apellidos);
                    spData.put("field_3", dni);
                    spData.put("field_5", tiendaReferencia);
                    spData.put("field_6", email);
                    spData.put("field_7", telefono);
                    // Formato fecha para SP: yyyy-MM-dd
                    spData.put("field_8", fechaNacimientoStr);
                    spData.put("field_9", cp);
                    spData.put("Verificado", false); // Se pondrá a true cuando confirme el email
                    spData.put("Title", nombre+" "+apellidos); 

                    String listId = SharepointUtil.getListId(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, SP_LIST_NAME);
                    
                    if (listId != null) {
                        if (isReactivation || existingUser != null) {
                            // Buscar ítem existente para actualizar
                            String itemId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, listId, "SqlRowUUID", sqlRowUuid);
                            if (itemId != null) {
                                spData.put("FechaBaja", null); // Limpiar fecha de baja en SP
                                FieldValueSet fieldsToUpdate = new FieldValueSet();
                                fieldsToUpdate.setAdditionalData(spData);
                                SharepointUtil.updateListItem(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, listId, itemId, fieldsToUpdate);
                            } else {
                                // No existe en SP aunque debería -> Crear
                                spData.put("SqlRowUUID", sqlRowUuid);
                                FieldValueSet fieldsToCreate = new FieldValueSet();
                                fieldsToCreate.setAdditionalData(spData);
                                SharepointUtil.createListItem(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, listId, fieldsToCreate);
                            }
                        } else {
                            // Nuevo registro puro
                            spData.put("SqlRowUUID", sqlRowUuid);
                            FieldValueSet fieldsToCreate = new FieldValueSet();
                            fieldsToCreate.setAdditionalData(spData);
                            SharepointUtil.createListItem(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, listId, fieldsToCreate);
                        }
                    } else {
                        logger.error("Lista SharePoint '{}' no encontrada. No se pudo replicar el registro.", SP_LIST_NAME);
                    }
                } catch (Exception e) {
                    logger.error("Fallo en replicación para UUID: {}. Causa: {}", sqlRowUuid, e.getMessage());
                    // No hacemos rollback de la DB local porque el usuario ya está creado
                }
            }

            // 3. Enviar email de verificación
            sendVerificationEmail(request, email, usuario, verificationToken);

            sendJsonResponse(response, HttpServletResponse.SC_OK, true, "¡Registro casi completo! Se ha enviado un correo de verificación a tu email. Por favor, revísalo para activar tu cuenta.", email);
            
        } catch (SQLException e) {
            logger.error("Error SQL durante registro de {}", usuario, e);
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { logger.warn("Rollback fallido", ex); }
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error de base de datos durante el registro.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { logger.warn("Error cerrando conexión", e); }
        }
    }
    
    private void sendVerificationEmail(HttpServletRequest request, String emailDestino, String usuario, String token) throws IOException {
        String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath();
        String link = baseUrl + "/verificar-email.html?token=" + token;
        
        final String username = Config.SMTP_USER;
        final String password = Config.SMTP_PASSWORD;

        Properties prop = new Properties();
        prop.put("mail.smtp.host", Config.SMTP_HOST);
        prop.put("mail.smtp.port", Config.SMTP_PORT);
        prop.put("mail.smtp.auth", "true");
        prop.put("mail.smtp.starttls.enable", "true");

        Session session = Session.getInstance(prop, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(username));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailDestino));
            message.setSubject("Verifica tu cuenta - Voluntarios Banco de Alimentos de Sevilla");

            String htmlContent = "<h1>¡Bienvenido/a!</h1>"
                    + "<p>Gracias por registrarte como voluntario. Para activar tu cuenta, por favor haz clic en el siguiente enlace:</p>"
                    + "<p><a href='" + link + "'>Verificar mi cuenta</a></p>"
                    + "<p>Si no has solicitado este registro, puedes ignorar este mensaje.</p>";

            message.setContent(htmlContent, "text/html; charset=utf-8");
            Transport.send(message);
            
            logger.debug("Correo de verificación enviado a {}", emailDestino);

        } catch (MessagingException e) {
            logger.error("Error enviando email de verificación a {}", emailDestino, e);
            // No lanzamos excepción para no mostrar error al usuario si el registro en DB fue exitoso,
            // pero el administrador debería revisar los logs.
        }
    }
    
    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
        sendJsonResponse(response, status, success, message, null);
    }
    
    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message, String email) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            Map<String, Object> jsonResponse = new HashMap<>();
            jsonResponse.put("success", success);
            jsonResponse.put("message", message);
            if (email != null) {
                jsonResponse.put("email", email);
            }
            mapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
}