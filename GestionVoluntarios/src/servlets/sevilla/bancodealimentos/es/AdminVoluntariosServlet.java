package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.FieldValueSet;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharePointUtil;

@WebServlet("/admin-voluntarios")
public class AdminVoluntariosServlet extends HttpServlet {
    private static final long serialVersionUID = 2L;
    private static final Logger logger = LoggerFactory.getLogger(AdminVoluntariosServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public static class Voluntario {
        public String usuario, nombre, apellidos, dni, email, telefono, cp, fechaNacimiento, esAdmin, fechaBaja, verificado;
    }

    private boolean isAdmin(HttpSession session) {
        if (session == null || session.getAttribute("usuario") == null) {
            return false;
        }
        Object isAdminAttr = session.getAttribute("isAdmin");
        if (isAdminAttr instanceof Boolean) {
            return (Boolean) isAdminAttr;
        }
        if (isAdminAttr instanceof String) {
            return "S".equalsIgnoreCase((String) isAdminAttr);
        }
        return false;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        HttpSession session = request.getSession(false);
        if (!isAdmin(session)) {
            sendJsonResponse(response, HttpServletResponse.SC_FORBIDDEN, false, "Acceso denegado.");
            return;
        }

        List<Voluntario> voluntarios = new ArrayList<>();
        String sql = "SELECT Usuario, Nombre, Apellidos, `DNI NIF`, Email, telefono, cp, fechaNacimiento, administrador, fecha_baja, verificado FROM voluntarios ORDER BY Apellidos, Nombre";
        
        try (Connection conn = DatabaseUtil.getConnection(); 
             PreparedStatement stmt = conn.prepareStatement(sql); 
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                Voluntario v = new Voluntario();
                v.usuario = rs.getString("Usuario");
                v.nombre = rs.getString("Nombre");
                v.apellidos = rs.getString("Apellidos");
                v.dni = rs.getString("DNI NIF");
                v.email = rs.getString("Email");
                v.telefono = rs.getString("telefono");
                v.cp = rs.getString("cp");
                v.fechaNacimiento = rs.getString("fechaNacimiento");
                v.esAdmin = rs.getString("administrador");
                v.verificado = rs.getString("verificado");
                String fb = rs.getString("fecha_baja");
                v.fechaBaja = (fb == null || fb.equals("0000-00-00 00:00:00")) ? null : fb;
                voluntarios.add(v);
            }
            mapper.writeValue(response.getWriter(), voluntarios);

        } catch (SQLException e) {
            LogUtil.logException(logger, e, "Error de BD al listar voluntarios", (String)session.getAttribute("usuario"));
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error de base de datos. El error ha sido registrado.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);
        if (!isAdmin(session)) {
            sendJsonResponse(response, HttpServletResponse.SC_FORBIDDEN, false, "Acceso denegado.");
            return;
        }

        String adminUser = (String) session.getAttribute("usuario");
        String action = request.getParameter("action");
        String voluntarioUsuario = request.getParameter("usuario");
        String context = String.format("Admin: %s, Action: %s, Voluntario: %s", adminUser, action, voluntarioUsuario);

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            String sqlRowUuid = findSqlRowUuid(conn, voluntarioUsuario);

            switch (action) {
                case "save":
                    updateVoluntario(conn, request, voluntarioUsuario);
                    syncVoluntario(conn, sqlRowUuid, request, voluntarioUsuario);
                    break;
                case "toggleAdmin":
                    toggleAdminStatus(conn, request.getParameter("esAdmin"), voluntarioUsuario);
                    break;
                case "toggleBaja":
                    boolean reactivar = Boolean.parseBoolean(request.getParameter("reactivar"));
                    toggleBajaStatus(conn, reactivar, voluntarioUsuario);
                    syncBajaStatus(conn, sqlRowUuid, reactivar, voluntarioUsuario);
                    break;
                default:
                    throw new ServletException("Acción no reconocida: " + action);
            }

            conn.commit();
            LogUtil.logOperation(conn, "ADMIN_ACTION_SUCCESS", adminUser, context);
            sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Operación completada con éxito.");

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { LogUtil.logException(logger, ex, "CRITICAL: Rollback fallido en acción de admin", context); }
            LogUtil.logException(logger, e, "Error en acción de admin", context);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error interno al procesar la operación. El problema ha sido registrado.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { LogUtil.logException(logger, e, "Error cerrando conexión en AdminVoluntariosServlet (POST)", adminUser); }
        }
    }

    private void updateVoluntario(Connection conn, HttpServletRequest request, String usuario) throws SQLException {
        String sql = "UPDATE voluntarios SET Nombre=?, Apellidos=?, `DNI NIF`=?, Email=?, telefono=?, cp=?, fechaNacimiento=?, notificar='S' WHERE Usuario=?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, request.getParameter("nombre"));
            stmt.setString(2, request.getParameter("apellidos"));
            stmt.setString(3, request.getParameter("dni"));
            stmt.setString(4, request.getParameter("email"));
            stmt.setString(5, request.getParameter("telefono"));
            stmt.setString(6, request.getParameter("cp"));
            stmt.setString(7, request.getParameter("fechaNacimiento"));
            stmt.setString(8, usuario);
            stmt.executeUpdate();
        }
    }

    private void syncVoluntario(Connection conn, String sqlRowUuid, HttpServletRequest request, String usuario) throws Exception {
        if (sqlRowUuid == null) throw new Exception("No se puede sincronizar: SqlRowUUID es nulo para el usuario " + usuario);
        
        FieldValueSet fields = new FieldValueSet();
        fields.getAdditionalData().put("field_1", request.getParameter("nombre"));
        fields.getAdditionalData().put("field_2", request.getParameter("apellidos"));
        fields.getAdditionalData().put("field_3", request.getParameter("dni"));
        fields.getAdditionalData().put("field_6", request.getParameter("email"));
        fields.getAdditionalData().put("field_7", request.getParameter("telefono"));
        fields.getAdditionalData().put("field_9", request.getParameter("cp"));
        fields.getAdditionalData().put("field_8", request.getParameter("fechaNacimiento"));

        updateSharePointItem(conn, sqlRowUuid, fields, usuario);
    }

    private void toggleAdminStatus(Connection conn, String esAdmin, String usuario) throws SQLException {
        String sql = "UPDATE voluntarios SET administrador=?, notificar='S' WHERE Usuario=?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, esAdmin);
            stmt.setString(2, usuario);
            stmt.executeUpdate();
        }
    }

    private void toggleBajaStatus(Connection conn, boolean reactivar, String usuario) throws SQLException {
        String sql = reactivar 
            ? "UPDATE voluntarios SET fecha_baja = NULL, notificar='S' WHERE Usuario=?"
            : "UPDATE voluntarios SET fecha_baja = ?, notificar='S' WHERE Usuario=?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (!reactivar) {
                stmt.setTimestamp(1, Timestamp.from(Instant.now()));
                stmt.setString(2, usuario);
            } else {
                stmt.setString(1, usuario);
            }
            stmt.executeUpdate();
        }
    }

    private void syncBajaStatus(Connection conn, String sqlRowUuid, boolean reactivar, String usuario) throws Exception {
        if (sqlRowUuid == null) throw new Exception("No se puede sincronizar: SqlRowUUID es nulo para el usuario " + usuario);

        FieldValueSet fields = new FieldValueSet();
        String isoDate = reactivar ? null : Instant.now().atZone(ZoneId.of("Europe/Madrid")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        fields.getAdditionalData().put("FechaBaja", isoDate);

        updateSharePointItem(conn, sqlRowUuid, fields, usuario);
    }

    private String findSqlRowUuid(Connection conn, String usuario) throws SQLException {
        String sql = "SELECT SqlRowUUID FROM voluntarios WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString("SqlRowUUID") : null;
            }
        }
    }

    private void updateSharePointItem(Connection conn, String sqlRowUuid, FieldValueSet fields, String usuario) throws Exception {
        String listId = SharePointUtil.getListId(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, "Voluntarios");
        if (listId == null) throw new Exception("La lista 'Voluntarios' de SharePoint no fue encontrada.");

        String itemId = SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, "SqlRowUUID", sqlRowUuid);
        if (itemId == null) throw new Exception("El voluntario '" + usuario + "' no fue encontrado en SharePoint.");

        SharePointUtil.updateListItem(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, itemId, fields);
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