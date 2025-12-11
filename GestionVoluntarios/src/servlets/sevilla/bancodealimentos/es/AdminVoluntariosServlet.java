package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharepointReplicationUtil;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/admin-voluntarios")
public class AdminVoluntariosServlet extends HttpServlet {
    private static final long serialVersionUID = 2L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(AdminVoluntariosServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    // DTO para enviar datos de voluntarios al frontend de forma limpia
    public static class VoluntarioDTO {
        public String usuario;
        public String nombre;
        public String apellidos;
        public String dni;
        public String email;
        public String telefono;
        public String cp;
        public String fechaNacimiento;
        public Integer tiendaReferencia;
        public String esAdmin;
    }

    // 3. Verificación de seguridad estandarizada
    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) return false;
        
        Object isAdminAttr = session.getAttribute("isAdmin");
        return (isAdminAttr instanceof Boolean && (Boolean) isAdminAttr) || 
               ("S".equals(isAdminAttr));
    }

    private String getUsuario(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("usuario") != null) {
            return (String) session.getAttribute("usuario");
        }
        return "sistema";
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (!isAdmin(request)) {
            logger.warn("Acceso denegado a AdminVoluntarios (GET). IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        List<VoluntarioDTO> voluntarios = new ArrayList<>();
        String sql = "SELECT Usuario, Nombre, Apellidos, `DNI NIF`, Email, telefono, administrador, cp, fechaNacimiento, tiendaReferencia FROM voluntarios ORDER BY Apellidos, Nombre";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                VoluntarioDTO v = new VoluntarioDTO();
                v.usuario = rs.getString("Usuario");
                v.nombre = rs.getString("Nombre");
                v.apellidos = rs.getString("Apellidos");
                v.dni = rs.getString("DNI NIF");
                v.email = rs.getString("Email");
                v.telefono = rs.getString("telefono");
                v.cp = rs.getString("cp");
                
                Date fecha = rs.getDate("fechaNacimiento");
                v.fechaNacimiento = (fecha != null) ? fecha.toString() : null;
                
                int tiendaRef = rs.getInt("tiendaReferencia");
                v.tiendaReferencia = rs.wasNull() ? null : tiendaRef;
                
                v.esAdmin = rs.getString("administrador");
                
                voluntarios.add(v);
            }
            
            // Serialización automática con Jackson
            mapper.writeValue(response.getWriter(), voluntarios);

        } catch (SQLException e) {
            logger.error("Error SQL al consultar los voluntarios.", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar los voluntarios.");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (!isAdmin(request)) {
            logger.warn("Acceso denegado a AdminVoluntarios (POST). IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }
        
        String action = request.getParameter("action");
        
        try {
            if ("toggleAdmin".equals(action)) {
                handleToggleAdmin(request, response);
            } else if ("save".equals(action)) {
                handleSave(request, response);
            } else {
                sendJsonResponse(response, false, "Acción desconocida.");
            }
        } catch (Exception e) {
            logger.error("Error no controlado en doPost", e);
            sendJsonResponse(response, false, "Error interno del servidor.");
        }
    }
    
    private void handleSave(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String usuarioToUpdate = request.getParameter("usuario");
        String adminUser = getUsuario(request);
        String sqlRowUuid = null;
        
        // Parámetros
        String nombre = request.getParameter("nombre");
        String apellidos = request.getParameter("apellidos");
        String dni = request.getParameter("dni");
        String email = request.getParameter("email");
        String telefono = request.getParameter("telefono");
        String fechaNacimientoStr = request.getParameter("fechaNacimiento");
        String cp = request.getParameter("cp");
        String tiendaReferenciaStr = request.getParameter("tiendaReferencia");

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            
            String sql = "UPDATE voluntarios SET Nombre = ?, Apellidos = ?, `DNI NIF` = ?, Email = ?, telefono = ?, fechaNacimiento = ?, cp = ?, tiendaReferencia = ?, notificar = 'S' WHERE Usuario = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, nombre);
                stmt.setString(2, apellidos);
                stmt.setString(3, dni);
                stmt.setString(4, email);
                stmt.setString(5, telefono);

                // Manejo de fecha
                if (fechaNacimientoStr != null && !fechaNacimientoStr.trim().isEmpty()) {
                    try {
                        stmt.setDate(6, Date.valueOf(fechaNacimientoStr));
                    } catch (IllegalArgumentException e) {
                        stmt.setNull(6, Types.DATE);
                    }
                } else {
                    stmt.setNull(6, Types.DATE);
                }
                
                stmt.setString(7, cp);

                // Manejo de tienda (int)
                if (tiendaReferenciaStr != null && !tiendaReferenciaStr.trim().isEmpty()) {
                    try {
                        stmt.setInt(8, Integer.parseInt(tiendaReferenciaStr));
                    } catch (NumberFormatException e) {
                        stmt.setNull(8, Types.INTEGER);
                    }
                } else {
                    stmt.setNull(8, Types.INTEGER);
                }
                
                stmt.setString(9, usuarioToUpdate);
                stmt.executeUpdate();
            }
            
            LogUtil.logOperation(conn, "ADMIN_UPDATE_VOL", adminUser, "Admin " + adminUser + " modificó datos de: " + usuarioToUpdate);
            
            sqlRowUuid = getSqlRowUuid(conn, usuarioToUpdate);
            conn.commit();
            
            // Replicación a SharePoint (si aplica)
            if (sqlRowUuid != null) {
                try {
                    Map<String, Object> spData = new HashMap<>();
                    spData.put("field_1", nombre);
                    spData.put("field_2", apellidos);
                    spData.put("field_3", dni);
                    spData.put("field_6", email);
                    spData.put("field_7", telefono);
                    
                    if (fechaNacimientoStr != null && !fechaNacimientoStr.isEmpty()) {
                         spData.put("field_8", fechaNacimientoStr);
                    }
                    if (cp != null && !cp.isEmpty()) {
                         spData.put("field_9", cp);
                    }
                    if (tiendaReferenciaStr != null && !tiendaReferenciaStr.isEmpty()) {
                        try {
                            spData.put("field_5", Integer.parseInt(tiendaReferenciaStr));
                        } catch (NumberFormatException e) { /* Ignorar */ }
                    }
                    
                    SharepointReplicationUtil.replicate(conn, SharepointUtil.SITE_ID, "Voluntarios", spData, SharepointReplicationUtil.Operation.UPDATE, sqlRowUuid);
                } catch (Exception e) {
                    logger.error("Fallo al replicar a SharePoint la modificación de {}", usuarioToUpdate, e);
                }
            }

            sendJsonResponse(response, true, "Datos del voluntario actualizados correctamente.");

        } catch (SQLException e) {
            logger.error("Error SQL al actualizar voluntario {}", usuarioToUpdate, e);
            sendJsonResponse(response, false, "Error al actualizar los datos (posible duplicado de DNI/Email).");
        }
    }

    private void handleToggleAdmin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String usuarioToUpdate = request.getParameter("usuario");
        String newAdminStatus = request.getParameter("esAdmin");
        String adminUser = getUsuario(request);
        String sqlRowUuid = null;

        if (usuarioToUpdate == null || newAdminStatus == null || (!"S".equals(newAdminStatus) && !"N".equals(newAdminStatus))) {
            sendJsonResponse(response, false, "Datos inválidos para la actualización.");
            return;
        }

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            
            String sql = "UPDATE voluntarios SET administrador = ?, notificar = 'S' WHERE Usuario = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, newAdminStatus);
                stmt.setString(2, usuarioToUpdate);
                stmt.executeUpdate();
            }
            
            LogUtil.logOperation(conn, "ADMIN_ROLE_CHANGE", adminUser, "Admin cambió rol de " + usuarioToUpdate + " a " + newAdminStatus);

            sqlRowUuid = getSqlRowUuid(conn, usuarioToUpdate);
            conn.commit();
            
            if (sqlRowUuid != null) {
                try {
                    Map<String, Object> spData = new HashMap<>();
                    spData.put("administrador", "S".equals(newAdminStatus) ? "Si" : "No");
                    SharepointReplicationUtil.replicate(conn, SharepointUtil.SITE_ID, "Voluntarios", spData, SharepointReplicationUtil.Operation.UPDATE, sqlRowUuid);
                } catch (Exception e) {
                    logger.error("Fallo al replicar a SharePoint el cambio de rol de {}", usuarioToUpdate, e);
                }
            }

            sendJsonResponse(response, true, "Rol de administrador actualizado correctamente.");

        } catch (SQLException e) {
            logger.error("Error SQL al cambiar rol de {}", usuarioToUpdate, e);
            sendJsonResponse(response, false, "Error al actualizar el rol.");
        }
    }
    
    private String getSqlRowUuid(Connection conn, String usuario) throws SQLException {
        String sql = "SELECT SqlRowUUID FROM voluntarios WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getString("SqlRowUUID");
            }
        }
        return null;
    }

    private void sendJsonResponse(HttpServletResponse response, boolean success, String message) throws IOException {
        Map<String, Object> json = new HashMap<>();
        json.put("success", success);
        json.put("message", message);
        mapper.writeValue(response.getWriter(), json);
    }
}