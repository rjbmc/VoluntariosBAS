package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

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

    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute("isAdmin") != null && (boolean) session.getAttribute("isAdmin");
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
        if (!isAdmin(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        StringBuilder jsonBuilder = new StringBuilder("[");

        String sql = "SELECT Usuario, Nombre, Apellidos, `DNI NIF`, Email, telefono, administrador, cp, fechaNacimiento, tiendaReferencia FROM voluntarios ORDER BY Apellidos, Nombre";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            boolean first = true;
            while (rs.next()) {
                if (!first) jsonBuilder.append(",");
                jsonBuilder.append("{");
                jsonBuilder.append("\"usuario\":\"").append(escapeJson(rs.getString("Usuario"))).append("\",");
                jsonBuilder.append("\"nombre\":\"").append(escapeJson(rs.getString("Nombre"))).append("\",");
                jsonBuilder.append("\"apellidos\":\"").append(escapeJson(rs.getString("Apellidos"))).append("\",");
                jsonBuilder.append("\"dni\":\"").append(escapeJson(rs.getString("DNI NIF"))).append("\",");
                jsonBuilder.append("\"email\":\"").append(escapeJson(rs.getString("Email"))).append("\",");
                jsonBuilder.append("\"telefono\":\"").append(escapeJson(rs.getString("telefono"))).append("\",");
                jsonBuilder.append("\"cp\":\"").append(escapeJson(rs.getString("cp"))).append("\",");
                jsonBuilder.append("\"fechaNacimiento\":\"").append(rs.getDate("fechaNacimiento")).append("\",");
                jsonBuilder.append("\"tiendaReferencia\":").append(rs.getInt("tiendaReferencia")).append(",");
                jsonBuilder.append("\"esAdmin\":\"").append(escapeJson(rs.getString("administrador"))).append("\"");
                jsonBuilder.append("}");
                first = false;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar los voluntarios.");
            return;
        }

        jsonBuilder.append("]");
        out.print(jsonBuilder.toString());
        out.flush();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!isAdmin(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Acceso denegado.");
            return;
        }
        
        request.setCharacterEncoding("UTF-8");
        String action = request.getParameter("action");
        
        if ("toggleAdmin".equals(action)) {
            handleToggleAdmin(request, response);
        } else if ("save".equals(action)) {
            handleSave(request, response);
        } else {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Acción desconocida.");
        }
    }
    
    private void handleSave(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String usuarioToUpdate = request.getParameter("usuario");
        String adminUser = getUsuario(request);
        String sqlRowUuid = null;
        
        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);
            
            String sql = "UPDATE voluntarios SET Nombre = ?, Apellidos = ?, `DNI NIF` = ?, Email = ?, telefono = ?, fechaNacimiento = ?, cp = ?, tiendaReferencia = ?, notificar = 'S' WHERE Usuario = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, request.getParameter("nombre"));
                stmt.setString(2, request.getParameter("apellidos"));
                stmt.setString(3, request.getParameter("dni"));
                stmt.setString(4, request.getParameter("email"));
                stmt.setString(5, request.getParameter("telefono"));
                stmt.setDate(6, java.sql.Date.valueOf(request.getParameter("fechaNacimiento")));
                stmt.setString(7, request.getParameter("cp"));
                stmt.setInt(8, Integer.parseInt(request.getParameter("tiendaReferencia")));
                stmt.setString(9, usuarioToUpdate);
                stmt.executeUpdate();
            }
            
            String logComment = "Admin " + adminUser + " modificó los datos del voluntario: " + usuarioToUpdate;
            LogUtil.logOperation(conn, "ADMIN_UPDATE_VOL", adminUser, logComment);
            
            sqlRowUuid = getSqlRowUuid(conn, usuarioToUpdate);
            conn.commit();
            
            if (sqlRowUuid != null) {
                try {
                    Map<String, Object> spData = new HashMap<>();
                    spData.put("field_1", request.getParameter("nombre"));
                    spData.put("field_2", request.getParameter("apellidos"));
                    spData.put("field_3", request.getParameter("dni"));
                    spData.put("field_5", Integer.parseInt(request.getParameter("tiendaReferencia")));
                    spData.put("field_6", request.getParameter("email"));
                    spData.put("field_7", request.getParameter("telefono"));
                    spData.put("field_8", request.getParameter("fechaNacimiento"));
                    spData.put("field_9", request.getParameter("cp"));
                    
                    // ** CORRECCIÓN: Añadido el Site ID como segundo parámetro **
                    SharepointReplicationUtil.replicate(conn, SharepointUtil.SP_SITE_ID_VOLUNTARIOS, "voluntarios", spData, SharepointReplicationUtil.Operation.UPDATE, sqlRowUuid);
                } catch (Exception e) {
                    System.err.println("ADVERTENCIA: Fallo al replicar a SharePoint la modificación de datos para " + usuarioToUpdate + ". Causa: " + e.getMessage());
                }
            }

            sendSuccess(response, "Datos del voluntario actualizados correctamente.");

        } catch (SQLException e) {
            e.printStackTrace();
            sendError(response, "Error al actualizar los datos. El DNI o el email podrían estar ya en uso.");
        } catch (Exception e) {
            e.printStackTrace();
            sendError(response, "Error en los datos enviados.");
        }
    }

    private void handleToggleAdmin(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        String usuarioToUpdate = request.getParameter("usuario");
        String newAdminStatus = request.getParameter("esAdmin");
        String adminUser = getUsuario(request);
        String sqlRowUuid = null;

        if (usuarioToUpdate == null || newAdminStatus == null || (!"S".equals(newAdminStatus) && !"N".equals(newAdminStatus))) {
            sendError(response, "Datos inválidos para la actualización.");
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
            
            String logComment = "Admin " + adminUser + " cambió el rol de " + usuarioToUpdate + " a admin=" + newAdminStatus;
            LogUtil.logOperation(conn, "ADMIN_ROLE_CHANGE", adminUser, logComment);

            sqlRowUuid = getSqlRowUuid(conn, usuarioToUpdate);
            conn.commit();
            
            if (sqlRowUuid != null) {
                try {
                    Map<String, Object> spData = new HashMap<>();
                    spData.put("field_10", "S".equals(newAdminStatus) ? "Si" : "No");
                    
                    // ** CORRECCIÓN: Añadido el Site ID como segundo parámetro **
                    SharepointReplicationUtil.replicate(conn, SharepointUtil.SP_SITE_ID_VOLUNTARIOS, "voluntarios", spData, SharepointReplicationUtil.Operation.UPDATE, sqlRowUuid);
                } catch (Exception e) {
                    System.err.println("ADVERTENCIA: Fallo al replicar a SharePoint el cambio de rol para " + usuarioToUpdate + ". Causa: " + e.getMessage());
                }
            }

            sendSuccess(response, "Rol de administrador actualizado correctamente.");

        } catch (SQLException e) {
            e.printStackTrace();
            sendError(response, "Error al actualizar el rol del voluntario.");
        }
    }
    
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

    private void sendSuccess(HttpServletResponse response, String message) throws IOException {
        response.getWriter().print("{\"success\": true, \"message\": \"" + message + "\"}");
    }

    private void sendError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.getWriter().print("{\"success\": false, \"message\": \"" + message + "\"}");
    }
    
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\b", "\\b").replace("\f", "\\f")
                  .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
