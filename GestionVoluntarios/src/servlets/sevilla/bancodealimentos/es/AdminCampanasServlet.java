// Paquete: servlets.sevilla.bancodealimentos.es
package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;

@WebServlet("/admin-campanas")
public class AdminCampanasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

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

        String sql = "SELECT Campana, denominacion, estado, fecha1, fecha2, turnospordia, Comentarios FROM campanas ORDER BY fecha1 DESC";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            boolean first = true;
            while (rs.next()) {
                if (!first) jsonBuilder.append(",");
                jsonBuilder.append("{");
                jsonBuilder.append("\"campana\":\"").append(escapeJson(rs.getString("Campana"))).append("\",");
                jsonBuilder.append("\"denominacion\":\"").append(escapeJson(rs.getString("denominacion"))).append("\",");
                jsonBuilder.append("\"estado\":\"").append(escapeJson(rs.getString("estado"))).append("\",");
                jsonBuilder.append("\"fecha1\":\"").append(rs.getDate("fecha1")).append("\",");
                jsonBuilder.append("\"fecha2\":\"").append(rs.getDate("fecha2")).append("\",");
                jsonBuilder.append("\"turnospordia\":").append(rs.getInt("turnospordia")).append(",");
                jsonBuilder.append("\"comentarios\":\"").append(escapeJson(rs.getString("Comentarios"))).append("\"");
                jsonBuilder.append("}");
                first = false;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error al consultar las campa�as.");
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
        
        if (action == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Acci�n no especificada.");
            return;
        }

        switch (action) {
            case "save":
                handleSave(request, response);
                break;
            case "delete":
                handleDelete(request, response);
                break;
            case "activate":
                handleActivate(request, response);
                break;
            case "deactivate":
                handleDeactivate(request, response);
                break;
            default:
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Acci�n desconocida.");
        }
    }

    private void handleSave(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String campanaId = request.getParameter("campanaId");
        String denominacion = request.getParameter("denominacion");
        String fecha1 = request.getParameter("fecha1");
        String fecha2 = request.getParameter("fecha2");
        String turnospordia = request.getParameter("turnospordia");
        String comentarios = request.getParameter("comentarios");
        String isUpdate = request.getParameter("isUpdate");
        String adminUser = getUsuario(request);

        String sql;
        String logComment;
        if ("true".equals(isUpdate)) {
            sql = "UPDATE campanas SET denominacion = ?, fecha1 = ?, fecha2 = ?, turnospordia = ?, Comentarios = ?, notificar = 'S' WHERE Campana = ?";
            logComment = "Admin " + adminUser + " modific� la campa�a: " + campanaId;
        } else {
            sql = "INSERT INTO campanas (denominacion, fecha1, fecha2, turnospordia, Comentarios, Campana, estado, notificar) VALUES (?, ?, ?, ?, ?, ?, 'N', 'S')";
            logComment = "Admin " + adminUser + " cre� la campa�a: " + campanaId;
        }

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, denominacion);
            stmt.setDate(2, java.sql.Date.valueOf(fecha1));
            stmt.setDate(3, java.sql.Date.valueOf(fecha2));
            stmt.setInt(4, Integer.parseInt(turnospordia));
            stmt.setString(5, comentarios);
            stmt.setString(6, campanaId);

            stmt.executeUpdate(); 
            
            LogUtil.logOperation(conn, "ADMIN_SAVE_CAMP", adminUser, logComment);

            sendSuccess(response, "Campa�a guardada correctamente.");

        } catch (SQLException e) {
            e.printStackTrace();
            sendError(response, "Error al guardar la campa�a. El c�digo de campa�a ya podr�a existir.");
        } catch (Exception e) {
            e.printStackTrace();
            sendError(response, "Datos inv�lidos.");
        }
    }

    private void handleDelete(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        String campanaId = request.getParameter("campanaId");
        String adminUser = getUsuario(request);
        String sql = "DELETE FROM campanas WHERE Campana = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, campanaId);
            stmt.executeUpdate();

            LogUtil.logOperation(conn, "ADMIN_DELETE_CAMP", adminUser, "Admin " + adminUser + " elimin� la campa�a: " + campanaId);

            sendSuccess(response, "Campa�a eliminada correctamente.");

        } catch (SQLException e) {
            e.printStackTrace();
            sendError(response, "Error al eliminar la campa�a. Aseg�rate de que no tenga voluntarios asignados.");
        }
    }

    private void handleActivate(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        String campanaId = request.getParameter("campanaId");
        String adminUser = getUsuario(request);
        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            String sqlDeactivate = "UPDATE campanas SET estado = 'N', notificar = 'S'";
            try (PreparedStatement stmt = conn.prepareStatement(sqlDeactivate)) {
                stmt.executeUpdate();
            }

            String sqlActivate = "UPDATE campanas SET estado = 'S', notificar = 'S' WHERE Campana = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlActivate)) {
                stmt.setString(1, campanaId);
                stmt.executeUpdate();
            }

            conn.commit();
            
            // --- CAMBIO: Se elimina el �ltimo par�metro de la llamada al log ---
            LogUtil.logOperation(conn, "ADMIN_ACTIVATE_CAMP", adminUser, "Admin " + adminUser + " activ� la campa�a: " + campanaId);

            sendSuccess(response, "Campa�a activada correctamente.");

        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            e.printStackTrace();
            sendError(response, "Error al activar la campa�a.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }

    private void handleDeactivate(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        String campanaId = request.getParameter("campanaId");
        String adminUser = getUsuario(request);
        String sql = "UPDATE campanas SET estado = 'N', notificar = 'S' WHERE Campana = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, campanaId);
            stmt.executeUpdate();

            LogUtil.logOperation(conn, "ADMIN_DEACTIVATE_CAMP", adminUser, "Admin " + adminUser + " desactiv� la campa�a: " + campanaId);
            sendSuccess(response, "Campa�a desactivada correctamente.");

        } catch (SQLException e) {
            e.printStackTrace();
            sendError(response, "Error al desactivar la campa�a.");
        }
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
