package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Period;
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
import util.sevilla.bancodealimentos.es.SharePointUtil;

@WebServlet("/nuevo-voluntario")
public class NuevoVoluntarioServlet extends HttpServlet {
    private static final long serialVersionUID = 3L;
    
    private static final Logger logger = LoggerFactory.getLogger(NuevoVoluntarioServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String SP_LIST_NAME = "Voluntarios";

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
        String puntoIdStr = request.getParameter("punto_id");
        int tiendaReferencia = (puntoIdStr != null && !puntoIdStr.isEmpty()) ? Integer.parseInt(puntoIdStr) : 0;

        // Validación de edad
        try {
            LocalDate fechaNacimiento = LocalDate.parse(fechaNacimientoStr);
            if (Period.between(fechaNacimiento, LocalDate.now()).getYears() < 16) {
                sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Debes tener al menos 16 años.");
                return;
            }
        } catch (Exception e) {
            sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Fecha de nacimiento no válida.");
            return;
        }
        
        Connection conn = null;
        String sqlRowUuid = null;
        boolean isReactivation = false;

        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            // 1. Comprobar existencia y estado de baja
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
                        isVerified = "S".equals(rs.getString("verificado"));
                        sqlRowUuid = rs.getString("SqlRowUUID");
                        
                        // CORRECCIÓN: Tratamos '0000-00-00' como NO baja
                        String fbStr = rs.getString("fecha_baja");
                        isInactive = (fbStr != null && !fbStr.equals("0000-00-00"));
                    }
                }
            }

            if (existingUser != null && !isInactive && isVerified) {
                sendJsonResponse(response, HttpServletResponse.SC_CONFLICT, false, "El usuario o DNI ya están registrados y activos.");
                conn.rollback();
                return;
            }

            String hashedPassword = PasswordUtils.hashPassword(clave);
            String verificationToken = UUID.randomUUID().toString();
            
            if (existingUser != null && isInactive) {
                isReactivation = true;
                String updateSql = "UPDATE voluntarios SET Nombre=?, Apellidos=?, `DNI NIF`=?, Clave=?, Email=?, telefono=?, fechaNacimiento=?, cp=?, tiendaReferencia=?, verificado='N', token_verificacion=?, fecha_baja=NULL, notificar='S' WHERE Usuario=?";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setString(1, nombre); ps.setString(2, apellidos); ps.setString(3, dni);
                    ps.setString(4, hashedPassword); ps.setString(5, email); ps.setString(6, telefono);
                    ps.setString(7, fechaNacimientoStr); ps.setString(8, cp); ps.setInt(9, tiendaReferencia);
                    ps.setString(10, verificationToken); ps.setString(11, existingUser);
                    ps.executeUpdate();
                }
            } else if (existingUser == null) {
                sqlRowUuid = UUID.randomUUID().toString();
                String insertSql = "INSERT INTO voluntarios (Usuario, Nombre, Apellidos, `DNI NIF`, Clave, Email, telefono, fechaNacimiento, cp, tiendaReferencia, verificado, token_verificacion, SqlRowUUID, notificar) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'N', ?, ?, 'S')";
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    ps.setString(1, usuario); ps.setString(2, nombre); ps.setString(3, apellidos);
                    ps.setString(4, dni); ps.setString(5, hashedPassword); ps.setString(6, email);
                    ps.setString(7, telefono); ps.setString(8, fechaNacimientoStr); ps.setString(9, cp);
                    ps.setInt(10, tiendaReferencia); ps.setString(11, verificationToken);
                    ps.setString(12, sqlRowUuid);
                    ps.executeUpdate();
                }
            } else {
                // Reintento de registro no verificado
                String updateSql = "UPDATE voluntarios SET Nombre=?, Apellidos=?, `DNI NIF`=?, Clave=?, Email=?, telefono=?, fechaNacimiento=?, cp=?, tiendaReferencia=?, token_verificacion=?, notificar='S' WHERE Usuario=?";
                try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                    ps.setString(1, nombre); ps.setString(2, apellidos); ps.setString(3, dni);
                    ps.setString(4, hashedPassword); ps.setString(5, email); ps.setString(6, telefono);
                    ps.setString(7, fechaNacimientoStr); ps.setString(8, cp); ps.setInt(9, tiendaReferencia);
                    ps.setString(10, verificationToken); ps.setString(11, existingUser);
                    ps.executeUpdate();
                }
            }

            conn.commit();
            LogUtil.logOperation(conn, "ALTA", usuario, isReactivation ? "Reactivación" : "Nuevo registro");

            // SharePoint Sync (Opcional)
            if (sqlRowUuid != null) {
                try {
                    syncSharePoint(nombre, apellidos, dni, tiendaReferencia, email, telefono, fechaNacimientoStr, cp, sqlRowUuid, isReactivation || existingUser != null);
                } catch (Exception e) { logger.error("Error SharePoint: {}", e.getMessage()); }
            }

            // 3. ENVIAR EMAIL Y VALIDAR ÉXITO
            boolean emailSent = sendVerificationEmail(request, email, usuario, verificationToken);

            if (emailSent) {
                sendJsonResponse(response, HttpServletResponse.SC_OK, true, "¡Registro completado! Por favor, verifica tu correo para activar la cuenta.", email);
            } else {
                sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Registro guardado, pero hubo un problema al enviar el correo de verificación. Contacta con soporte.", email);
            }
            
        } catch (SQLException e) {
            logger.error("Error DB: {}", e.getMessage());
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) {}
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error de base de datos.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) {}
        }
    }

    private void syncSharePoint(String nom, String ape, String dni, int tr, String em, String tel, String fn, String cp, String uuid, boolean update) throws Exception {
        Map<String, Object> spData = new HashMap<>();
        spData.put("field_1", nom); spData.put("field_2", ape); spData.put("field_3", dni);
        spData.put("field_5", tr); spData.put("field_6", em); spData.put("field_7", tel);
        spData.put("field_8", fn); spData.put("field_9", cp);
        spData.put("Verificado", false); spData.put("Title", nom + " " + ape);

        String listId = SharePointUtil.getListId(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, SP_LIST_NAME);
        if (listId == null) return;

        if (update) {
            String itemId = SharePointUtil.findItemIdByFieldValue(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, "SqlRowUUID", uuid);
            if (itemId != null) {
                spData.put("FechaBaja", null);
                FieldValueSet f = new FieldValueSet(); f.setAdditionalData(spData);
                SharePointUtil.updateListItem(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, itemId, f);
                return;
            }
        }
        spData.put("SqlRowUUID", uuid);
        FieldValueSet f = new FieldValueSet(); f.setAdditionalData(spData);
        SharePointUtil.createListItem(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, f);
    }

    /**
     * Envía el email y devuelve true si se envió correctamente.
     */
    private boolean sendVerificationEmail(HttpServletRequest request, String emailDestino, String usuario, String token) {
        String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath();
        String link = baseUrl + "/verificar-email.html?token=" + token;
        
        Properties prop = new Properties();
        prop.put("mail.smtp.host", Config.SMTP_HOST);
        prop.put("mail.smtp.port", Config.SMTP_PORT);
        prop.put("mail.smtp.auth", "true");
        prop.put("mail.smtp.starttls.enable", "true");

        Session session = Session.getInstance(prop, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(Config.SMTP_USER, Config.SMTP_PASSWORD);
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(Config.SMTP_USER));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailDestino));
            message.setSubject("Verifica tu cuenta - Banco de Alimentos");

            String htmlContent = "<h1>¡Bienvenido/a!</h1>"
                    + "<p>Para activar tu cuenta, haz clic aquí:</p>"
                    + "<p><a href='" + link + "'>Verificar mi cuenta</a></p>";

            message.setContent(htmlContent, "text/html; charset=utf-8");
            Transport.send(message);
            return true;
        } catch (MessagingException e) {
            logger.error("Fallo crítico enviando email a {}: {}", emailDestino, e.getMessage());
            return false;
        }
    }

    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
        sendJsonResponse(response, status, success, message, null);
    }

    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message, String email) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            Map<String, Object> res = new HashMap<>();
            res.put("success", success);
            res.put("message", message);
            if (email != null) res.put("email", email);
            mapper.writeValue(response.getWriter(), res);
        }
    }
}