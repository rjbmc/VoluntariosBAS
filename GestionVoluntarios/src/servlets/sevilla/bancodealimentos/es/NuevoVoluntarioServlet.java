package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
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
    private static final long serialVersionUID = 5L;
    private static final Logger logger = LoggerFactory.getLogger(NuevoVoluntarioServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

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
        int tiendaReferencia = parseOptionalInt(request.getParameter("punto_id"));

        String context = String.format("Usuario: %s, DNI: %s, Email: %s", usuario, dni, email);

        if (!isDataValid(usuario, nombre, apellidos, dni, clave, email, fechaNacimientoStr)) {
            sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Faltan campos obligatorios.");
            return;
        }
        
        try {
            if (Period.between(LocalDate.parse(fechaNacimientoStr), LocalDate.now()).getYears() < 16) {
                sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Debes tener al menos 16 años.");
                return;
            }
        } catch (Exception e) {
            sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Fecha de nacimiento no válida.");
            return;
        }

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            CheckUserResult userCheck = checkExistingUser(conn, usuario, dni);

            if (userCheck.exists && !userCheck.isInactive && userCheck.isVerified) {
                sendJsonResponse(response, HttpServletResponse.SC_CONFLICT, false, "El usuario o DNI ya están registrados y activos.");
                conn.rollback();
                return;
            }
            
            String hashedPassword = PasswordUtils.hashPassword(clave);
            String verificationToken = UUID.randomUUID().toString();
            String sqlRowUuid = userCheck.sqlRowUuid != null ? userCheck.sqlRowUuid : UUID.randomUUID().toString();
            boolean isReactivation = userCheck.exists && userCheck.isInactive;

            saveUserToDb(conn, userCheck, usuario, nombre, apellidos, dni, hashedPassword, email, telefono, fechaNacimientoStr, cp, tiendaReferencia, verificationToken, sqlRowUuid);
            syncSharePoint(conn, sqlRowUuid, nombre, apellidos, dni, tiendaReferencia, email, telefono, fechaNacimientoStr, cp, isReactivation || (userCheck.exists && !userCheck.isVerified));
            sendVerificationEmail(request, email, nombre, verificationToken);
            
            conn.commit();
            LogUtil.logOperation(conn, "REGISTER", usuario, (isReactivation ? "Reactivación de cuenta." : "Nueva alta.") + " Email de verificación enviado.");
            sendJsonResponse(response, HttpServletResponse.SC_OK, true, "¡Registro completado! Por favor, verifica tu correo para activar la cuenta.");

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { LogUtil.logException(logger, ex, "CRITICAL: Rollback fallido en registro", context); }
            LogUtil.logException(logger, e, "Error en el proceso de registro", context);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error interno al procesar el registro. El problema ha sido registrado.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { LogUtil.logException(logger, e, "Error cerrando conexión en NuevoVoluntarioServlet", context); }
        }
    }

    private CheckUserResult checkExistingUser(Connection conn, String usuario, String dni) throws SQLException {
        String sql = "SELECT Usuario, fecha_baja, verificado, SqlRowUUID FROM voluntarios WHERE Usuario = ? OR `DNI NIF` = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, usuario);
            ps.setString(2, dni);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String fbStr = rs.getString("fecha_baja");
                    boolean isInactive = (fbStr != null && !fbStr.equals("0000-00-00"));
                    return new CheckUserResult(true, isInactive, "S".equals(rs.getString("verificado")), rs.getString("Usuario"), rs.getString("SqlRowUUID"));
                }
                return new CheckUserResult(false, false, false, null, null);
            }
        }
    }

    private void saveUserToDb(Connection conn, CheckUserResult userCheck, String usuario, String nombre, String apellidos, String dni, String hashedPassword, String email, String telefono, String fechaNacimientoStr, String cp, int tiendaReferencia, String verificationToken, String sqlRowUuid) throws SQLException {
        String sql;
        if (userCheck.exists && userCheck.isInactive) { // Reactivación
            sql = "UPDATE voluntarios SET Nombre=?, Apellidos=?, `DNI NIF`=?, Clave=?, Email=?, telefono=?, fechaNacimiento=?, cp=?, tiendaReferencia=?, verificado='N', token_verificacion=?, fecha_baja=NULL, notificar='S' WHERE Usuario=?";
            try(PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, nombre); ps.setString(2, apellidos); ps.setString(3, dni); ps.setString(4, hashedPassword); ps.setString(5, email);
                ps.setString(6, telefono); ps.setString(7, fechaNacimientoStr); ps.setString(8, cp); ps.setInt(9, tiendaReferencia);
                ps.setString(10, verificationToken); ps.setString(11, userCheck.username);
                ps.executeUpdate();
            }
        } else if (userCheck.exists && !userCheck.isVerified) { // Actualizar datos de un no verificado
            sql = "UPDATE voluntarios SET Nombre=?, Apellidos=?, Clave=?, Email=?, telefono=?, fechaNacimiento=?, cp=?, tiendaReferencia=?, token_verificacion=?, notificar='S' WHERE Usuario=?";
             try(PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, nombre); ps.setString(2, apellidos); ps.setString(3, hashedPassword); ps.setString(4, email);
                ps.setString(5, telefono); ps.setString(6, fechaNacimientoStr); ps.setString(7, cp); ps.setInt(8, tiendaReferencia);
                ps.setString(9, verificationToken); ps.setString(10, userCheck.username);
                ps.executeUpdate();
            }
        } else { // Nuevo
            sql = "INSERT INTO voluntarios (Usuario, Nombre, Apellidos, `DNI NIF`, Clave, Email, telefono, fechaNacimiento, cp, tiendaReferencia, verificado, token_verificacion, SqlRowUUID, notificar) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'N', ?, ?, 'S')";
            try(PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, usuario); ps.setString(2, nombre); ps.setString(3, apellidos); ps.setString(4, dni);
                ps.setString(5, hashedPassword); ps.setString(6, email); ps.setString(7, telefono); ps.setString(8, fechaNacimientoStr);
                ps.setString(9, cp); ps.setInt(10, tiendaReferencia); ps.setString(11, verificationToken); ps.setString(12, sqlRowUuid);
                ps.executeUpdate();
            }
        }
    }

    private void syncSharePoint(Connection conn, String uuid, String nom, String ape, String dni, int tr, String em, String tel, String fn, String cp, boolean isUpdate) throws Exception {
        String listId = SharePointUtil.getListId(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, "Voluntarios");
        if (listId == null) throw new Exception("No se encontró la lista de Voluntarios en SharePoint.");

        FieldValueSet fields = new FieldValueSet();
        fields.getAdditionalData().put("Title", nom + " " + ape);
        fields.getAdditionalData().put("field_1", nom); fields.getAdditionalData().put("field_2", ape);
        fields.getAdditionalData().put("field_3", dni); fields.getAdditionalData().put("field_5", tr > 0 ? tr : null);
        fields.getAdditionalData().put("field_6", em); fields.getAdditionalData().put("field_7", tel);
        fields.getAdditionalData().put("field_8", fn); fields.getAdditionalData().put("field_9", cp);
        fields.getAdditionalData().put("Verificado", false);

        if (isUpdate) {
            String itemId = SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, "SqlRowUUID", uuid);
            if (itemId != null) {
                SharePointUtil.updateListItem(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, itemId, fields);
                return;
            }
        }
        fields.getAdditionalData().put("SqlRowUUID", uuid);
        SharePointUtil.createListItem(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, fields);
    }

    private void sendVerificationEmail(HttpServletRequest request, String emailDestino, String nombre, String token) throws MessagingException, UnsupportedEncodingException {
        String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath();
        String link = baseUrl + "/verificar-email.html?token=" + token;
        
        Properties prop = new Properties();
        prop.put("mail.smtp.host", Config.SMTP_HOST); prop.put("mail.smtp.port", Config.SMTP_PORT);
        prop.put("mail.smtp.auth", "true"); prop.put("mail.smtp.starttls.enable", "true");

        Session session = Session.getInstance(prop, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() { return new PasswordAuthentication(Config.SMTP_USER, Config.SMTP_PASSWORD); }
        });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(Config.SMTP_USER, "Banco de Alimentos de Sevilla"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailDestino));
        message.setSubject("Verifica tu cuenta de voluntario/a");
        
        String htmlContent = "<h1>¡Bienvenido/a, " + nombre + "!</h1>"
                    + "<p>Gracias por registrarte. Para completar el proceso y activar tu cuenta, por favor haz clic en el siguiente enlace:</p>"
                    + "<h2><a href=\"" + link + "\">Activar mi cuenta</a></h2>"
                    + "<p>Si no te has registrado, puedes ignorar este correo.</p>"
                    + "<p>Un saludo,<br>El equipo del Banco de Alimentos de Sevilla</p>";

        message.setContent(htmlContent, "text/html; charset=utf-8");
        Transport.send(message);
    }

    private static class CheckUserResult {
        final boolean exists, isInactive, isVerified;
        final String username, sqlRowUuid;
        CheckUserResult(boolean e, boolean i, boolean v, String user, String id) { this.exists = e; this.isInactive = i; this.isVerified = v; this.username = user; this.sqlRowUuid = id; }
    }
    
    private boolean isDataValid(String... params) {
        for (String p : params) {
            if (p == null || p.trim().isEmpty()) return false;
        }
        return true;
    }

    private int parseOptionalInt(String s) {
        try { return (s != null && !s.isEmpty()) ? Integer.parseInt(s) : 0; } catch (NumberFormatException e) { return 0; }
    }

    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            Map<String, Object> res = new HashMap<>();
            res.put("success", success);
            res.put("message", message);
            mapper.writeValue(response.getWriter(), res);
        }
    }
}
