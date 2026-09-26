import React from 'react';
import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import KioskGate from './KioskGate';
import SecurityAudit from './SecurityAudit';
import AdminLayout from './AdminLayout';
import Overview from './Overview';
import AddUser from './AddUser';
import EmployeeList from './EmployeeList';
import './index.css';
import { AuthProvider, ProtectedAdmin } from './Auth';
import Login from './Login';
import SecuritySettings from './SecuritySettings';

function App() {
  return (
    <Router>
      <AuthProvider>
        <Routes>
          <Route path="/" element={<Navigate to="/kiosk" replace />} />
          <Route path="/kiosk" element={<KioskGate />} />
          <Route path="/login" element={<Login />} />
          <Route element={<ProtectedAdmin />}>
            <Route path="/admin" element={<AdminLayout />}>
              <Route index element={<Overview />} />
              <Route path="add-user" element={<AddUser />} />
              <Route path="users" element={<EmployeeList />} />
              <Route path="security" element={<SecuritySettings />} />
              <Route path="audit" element={<SecurityAudit />} />
            </Route>
          </Route>
        </Routes>
      </AuthProvider>
    </Router>
  );
}

export default App;
